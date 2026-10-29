-- =========================================================
-- 분기점 목표 차로 + 차로별 진행 방향 계산 (RoadRepository.TARGET_LANES_SQL 과 같은 내용)
-- ? 순서: 분기점 앞 지점(lng, lat), 그보다 조금 앞 점(lng, lat), 분기점 앞 지점(lng, lat),
--         분기점 뒤 지점(lng, lat), 그보다 조금 앞 점(lng, lat), 분기점 뒤 지점(lng, lat),
--         차로를 따라갈 최대 거리(m)
-- 결과: pos(왼쪽부터 차로 번호), total(전체 차로 수), target(목표 차로 여부),
--       dirs(그 차로의 실제 진행 방향, 예: 'STRAIGHT,RIGHT')
-- 방법: 1) 분기점 앞/뒤 지점에서 진행 방향이 같은 가장 가까운 차로를 찾고
--       2) l_linkid / r_linkid 로 그 단면의 모든 차로를 모아 왼쪽부터 번호를 매긴 뒤
--       3) 차선 변경 엣지(LC)를 뺀 그래프에서 분기점 뒤 도로까지 이어지는 차로 = 목표 차로
--       4) 각 차로에서 교차로 링크를 따라가 교차로를 빠져나가는 방향으로 좌/직/우/유턴을 정한다
-- =========================================================
WITH RECURSIVE
p_a AS (
  SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179) AS g,
         ST_Azimuth(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179),
                    ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179)) AS az
),
cand_a AS (
  SELECT l.id, l.geom <-> p_a.g AS d,
         abs(mod((ST_Azimuth(ST_LineInterpolatePoint(ln, greatest(f - 0.05, 0)),
                             ST_LineInterpolatePoint(ln, least(f + 0.05, 1))) - p_a.az + 3 * pi())::numeric,
                 (2 * pi())::numeric) - pi()::numeric) AS da
  FROM a2_link l, p_a,
       LATERAL (SELECT ST_GeometryN(ST_LineMerge(ST_Force2D(l.geom)), 1) AS ln) x,
       LATERAL (SELECT ST_LineLocatePoint(x.ln, p_a.g) AS f) y
  WHERE l.linktype <> '1' AND ST_DWithin(l.geom, p_a.g, 25)
),
seed_a AS (SELECT id FROM cand_a WHERE da < radians(60) ORDER BY d LIMIT 1),
lanes_a AS (
  SELECT l.id, l.r_linkid, l.l_linkid FROM a2_link l JOIN seed_a s ON l.id = s.id
  UNION
  SELECT n.id, n.r_linkid, n.l_linkid FROM a2_link n JOIN lanes_a ON n.id = lanes_a.r_linkid OR n.id = lanes_a.l_linkid
),
ordered_a AS (
  SELECT id, r_linkid, 1 AS pos FROM lanes_a
  WHERE l_linkid IS NULL OR l_linkid NOT IN (SELECT id FROM lanes_a)
  UNION ALL
  SELECT n.id, n.r_linkid, o.pos + 1 FROM lanes_a n JOIN ordered_a o ON n.id = o.r_linkid WHERE o.pos < 20
),
p_b AS (
  SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179) AS g,
         ST_Azimuth(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179),
                    ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179)) AS az
),
cand_b AS (
  SELECT l.id, l.geom <-> p_b.g AS d,
         abs(mod((ST_Azimuth(ST_LineInterpolatePoint(ln, greatest(f - 0.05, 0)),
                             ST_LineInterpolatePoint(ln, least(f + 0.05, 1))) - p_b.az + 3 * pi())::numeric,
                 (2 * pi())::numeric) - pi()::numeric) AS da
  FROM a2_link l, p_b,
       LATERAL (SELECT ST_GeometryN(ST_LineMerge(ST_Force2D(l.geom)), 1) AS ln) x,
       LATERAL (SELECT ST_LineLocatePoint(x.ln, p_b.g) AS f) y
  WHERE l.linktype <> '1' AND ST_DWithin(l.geom, p_b.g, 25)
),
seed_b AS (SELECT id FROM cand_b WHERE da < radians(60) ORDER BY d LIMIT 1),
lanes_b AS (
  SELECT l.id, l.r_linkid, l.l_linkid FROM a2_link l JOIN seed_b s ON l.id = s.id
  UNION
  SELECT n.id, n.r_linkid, n.l_linkid FROM a2_link n JOIN lanes_b ON n.id = lanes_b.r_linkid OR n.id = lanes_b.l_linkid
),
ordered_b AS (
  SELECT id, r_linkid, 1 AS pos FROM lanes_b
  WHERE l_linkid IS NULL OR l_linkid NOT IN (SELECT id FROM lanes_b)
  UNION ALL
  SELECT n.id, n.r_linkid, o.pos + 1 FROM lanes_b n JOIN ordered_b o ON n.id = o.r_linkid WHERE o.pos < 20
),
xa AS (SELECT o.id, o.pos, e.source FROM ordered_a o JOIN route_edge e ON e.link_id = o.id),
xb AS (SELECT o.id, e.target FROM ordered_b o JOIN route_edge e ON e.link_id = o.id),
reach AS (
  SELECT DISTINCT d.start_vid
  FROM pgr_dijkstra(
         'SELECT id, source, target, cost, reverse_cost FROM route_edge WHERE link_id <> ''LC''',
         (SELECT array_agg(source) FROM xa),
         (SELECT array_agg(target) FROM xb),
         directed := true) d
  WHERE d.node = d.end_vid AND d.agg_cost <= ?
),
-- 차로별 실제 진행 방향: 각 차로에서 차선 변경 없이 따라가 교차로 링크(linktype '1')를 찾는다
-- entry_id = 교차로에 들어가기 직전 링크, link_id = 지금 지나는 링크
walk AS (
  SELECT o.pos, e.target AS node, o.id::varchar AS entry_id, o.id::varchar AS link_id,
         false AS in_x, false AS done, 0::numeric AS dist, 0 AS depth
  FROM ordered_a o JOIN route_edge e ON e.link_id = o.id
  UNION ALL
  SELECT w.pos, e.target,
         CASE WHEN l.linktype = '1' OR w.in_x THEN w.entry_id ELSE l.id END,
         l.id, l.linktype = '1', w.in_x AND l.linktype <> '1',
         w.dist + e.cost, w.depth + 1
  FROM walk w
  JOIN route_edge e ON e.source = w.node AND e.link_id <> 'LC'
  JOIN a2_link l ON l.id = e.link_id
  WHERE NOT w.done AND w.depth < 8 AND w.dist < 150
),
-- 교차로 안 마지막 링크(다음에 교차로 링크가 더 없는 것)의 끝 방향을, 진입 링크 끝 방향과 비교한 회전각(도, 시계 방향 +)
exits AS (
  SELECT DISTINCT w.pos,
         degrees(mod((ST_Azimuth(ST_LineInterpolatePoint(xo.ln, greatest(0, 1 - 15 / ST_Length(xo.ln))), ST_EndPoint(xo.ln))
                      - ST_Azimuth(ST_LineInterpolatePoint(xi.ln, greatest(0, 1 - 15 / ST_Length(xi.ln))), ST_EndPoint(xi.ln))
                      + 3 * pi())::numeric, (2 * pi())::numeric) - pi()::numeric) AS deg
  FROM walk w
  JOIN a2_link li ON li.id = w.entry_id
  JOIN a2_link lo ON lo.id = w.link_id,
       LATERAL (SELECT ST_GeometryN(ST_LineMerge(ST_Force2D(li.geom)), 1) AS ln) xi,
       LATERAL (SELECT ST_GeometryN(ST_LineMerge(ST_Force2D(lo.geom)), 1) AS ln) xo
  WHERE w.in_x
    AND NOT EXISTS (SELECT 1 FROM route_edge e2 JOIN a2_link l2 ON l2.id = e2.link_id
                    WHERE e2.source = w.node AND l2.linktype = '1')
),
-- 직진 = 회전각이 가장 작은 출구(60도 안쪽일 때만). 도로가 휘어 있어도 그 도로를 따라가는 쪽이 직진이다
straight AS (SELECT deg FROM exits WHERE abs(deg) < 60 ORDER BY abs(deg) LIMIT 1),
turns AS (
  SELECT DISTINCT e.pos,
         CASE WHEN abs(e.deg) > 150 THEN 'UTURN'
              WHEN abs(e.deg - s.deg) <= 30 THEN 'STRAIGHT'
              WHEN e.deg < coalesce(s.deg, 0) THEN 'LEFT'
              ELSE 'RIGHT' END AS turn
  FROM exits e LEFT JOIN straight s ON true
)
SELECT a.pos, (SELECT count(*) FROM xa) AS total, (a.source IN (SELECT start_vid FROM reach)) AS target,
       (SELECT string_agg(t.turn, ',') FROM turns t WHERE t.pos = a.pos) AS dirs
FROM xa a ORDER BY a.pos;
