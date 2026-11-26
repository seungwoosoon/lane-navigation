-- =========================================================
-- 분기점 목표 차로 + 차로별 진행 방향 계산 (RoadRepository.TARGET_LANES_SQL 과 같은 내용)
-- ? 순서: 분기점 앞 지점(lng, lat), 그보다 조금 앞 점(lng, lat), 분기점 앞 지점(lng, lat),
--         분기점 뒤 지점(lng, lat), 그보다 조금 앞 점(lng, lat), 분기점 뒤 지점(lng, lat),
--         차로를 따라갈 최대 거리(m)
-- 결과: pos(왼쪽부터 차로 번호), link_id(그 차로 링크), total(전체 차로 수), target(목표 차로 여부),
--       dirs(그 차로의 실제 진행 방향, 예: 'STRAIGHT,RIGHT'), maneuver_link(회전이 시작되는 링크, Lane AI 요청용)
-- 방법: 1) 분기점 앞/뒤 지점에서 진행 방향이 같은 가장 가까운 차로를 찾고
--       2) l_linkid / r_linkid 로 그 단면의 모든 차로를 모아 왼쪽부터 번호를 매긴 뒤
--       3) 차선 변경 엣지(LC)를 뺀 그래프에서 분기점 뒤 도로까지 이어지는 차로 = 목표 차로
--       4) 각 차로에서 갈 수 있는 길을 모두 따라가, 교차로를 빠져나가거나 교차로 앞에서 꺾여 나가는(도류화)
--          방향으로 좌/직/우/유턴을 정한다
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
  SELECT d.start_vid, d.end_vid, d.agg_cost
  FROM pgr_dijkstra(
         'SELECT id, source, target, cost, reverse_cost FROM route_edge WHERE link_id <> ''LC''',
         (SELECT array_agg(source) FROM xa),
         (SELECT array_agg(target) FROM xb),
         directed := true) d
  WHERE d.node = d.end_vid AND d.agg_cost <= ?
),
-- ---- 차로별 실제 진행 방향 ----
-- 주변 링크의 시작/끝 방향(라디안, 북쪽 기준 시계 방향)
lh AS (
  SELECT l.id, l.linktype,
         ST_Azimuth(ST_StartPoint(x.ln), ST_LineInterpolatePoint(x.ln, least(1, 15 / ST_Length(x.ln)))) AS az_start,
         ST_Azimuth(ST_LineInterpolatePoint(x.ln, greatest(0, 1 - 15 / ST_Length(x.ln))), ST_EndPoint(x.ln)) AS az_end
  FROM a2_link l, p_a, LATERAL (SELECT ST_GeometryN(ST_LineMerge(ST_Force2D(l.geom)), 1) AS ln) x
  WHERE ST_DWithin(l.geom, p_a.g, 300) AND ST_Length(x.ln) > 0
),
-- 각 차로에서 차선 변경 없이 갈 수 있는 길을 모두 따라간다. 멈추는 곳(done):
--   1) 교차로 링크(linktype '1')를 지나 일반 링크로 빠져나온 곳
--   2) 교차로를 안 거치고 40도 넘게 꺾인 곳 (도류화 우회전처럼 교차로 앞에서 갈라지는 길)
walk AS (
  SELECT o.pos, e.target AS node, h.az_end AS ref, o.id::varchar AS link_id,
         false AS in_x, false AS done, 0::numeric AS dist, 0 AS depth
  FROM ordered_a o JOIN route_edge e ON e.link_id = o.id JOIN lh h ON h.id = o.id
  UNION ALL
  SELECT w.pos, e.target, w.ref, h.id,
         w.in_x OR h.linktype = '1',
         h.linktype <> '1' AND (w.in_x OR abs(degrees(mod((h.az_end - w.ref + 3 * pi())::numeric, (2 * pi())::numeric) - pi()::numeric)) > 40),
         w.dist + e.cost, w.depth + 1
  FROM walk w
  JOIN route_edge e ON e.source = w.node AND e.link_id <> 'LC'
  JOIN lh h ON h.id = e.link_id
  WHERE NOT w.done AND w.depth < 8 AND w.dist < 150
),
-- 더 따라가지 않는 끝 지점들의 회전각(도, 시계 방향 +).
-- 교차로를 빠져나온 링크는 시작 방향을 본다. 교차로 없이 꺾인 링크(도류화 우회전, 유턴 연결 링크)는
-- 꺾이는 중간에 끝날 수 있어서 그다음 링크의 시작 방향을 보고, 다음 링크가 없으면 끝 방향을 본다
exits AS (
  SELECT DISTINCT w.pos,
         degrees(mod(((CASE
             WHEN w.in_x AND h.linktype <> '1' THEN h.az_start
             WHEN w.done THEN coalesce(
               (SELECT h2.az_start FROM route_edge e2 JOIN lh h2 ON h2.id = e2.link_id
                WHERE e2.source = w.node AND e2.link_id <> 'LC'
                ORDER BY abs(mod((h2.az_start - h.az_end + 3 * pi())::numeric, (2 * pi())::numeric) - pi()::numeric)
                LIMIT 1), h.az_end)
             ELSE h.az_end END) - w.ref + 3 * pi())::numeric, (2 * pi())::numeric) - pi()::numeric) AS deg
  FROM walk w JOIN lh h ON h.id = w.link_id
  WHERE w.done OR w.depth >= 8 OR w.dist >= 150
     OR NOT EXISTS (SELECT 1 FROM route_edge e2 JOIN lh h2 ON h2.id = e2.link_id
                    WHERE e2.source = w.node AND e2.link_id <> 'LC')
),
-- 직진 = 회전각이 가장 작은 끝 지점(60도 안쪽일 때만). 도로가 휘어 있어도 그 도로를 따라가는 쪽이 직진이다
straight AS (SELECT deg FROM exits WHERE abs(deg) < 60 ORDER BY abs(deg) LIMIT 1),
-- 한 차로에서 갈라지는 길이 여러 개면 직진에 가장 가까운 길만 직진이고, 나머지는 꺾인 쪽으로 본다
turns AS (
  SELECT DISTINCT e.pos,
         CASE WHEN abs(e.deg) > 150 THEN 'UTURN'
              WHEN abs(e.deg - s.deg) <= 30
                   AND abs(e.deg - s.deg) <= (SELECT min(abs(e2.deg - s.deg)) FROM exits e2 WHERE e2.pos = e.pos) + 5
                THEN 'STRAIGHT'
              WHEN e.deg < coalesce(s.deg, 0) THEN 'LEFT'
              ELSE 'RIGHT' END AS turn
  FROM exits e LEFT JOIN straight s ON true
),
-- ---- 회전 연결 링크 (Lane AI 요청의 maneuverLinkId) ----
-- 목표 차로에서 분기점 뒤까지 가장 짧은 길을 따라가다 처음으로 회전이 시작되는 링크:
-- 교차로 링크(linktype '1')이거나, 출발 차로 방향에서 40도 넘게 꺾이는 링크(유턴 연결 링크 등)
best AS (SELECT start_vid, end_vid FROM reach ORDER BY agg_cost LIMIT 1),
tpath AS (
  SELECT d.seq, e.link_id
  FROM best CROSS JOIN LATERAL pgr_dijkstra(
         'SELECT id, source, target, cost, reverse_cost FROM route_edge WHERE link_id <> ''LC''',
         best.start_vid, best.end_vid, directed := true) d
  JOIN route_edge e ON e.id = d.edge
),
conn AS (
  SELECT t.link_id
  FROM tpath t JOIN lh h ON h.id = t.link_id,
       (SELECT h0.az_end FROM tpath t0 JOIN lh h0 ON h0.id = t0.link_id ORDER BY t0.seq LIMIT 1) r
  WHERE t.seq > 1
    AND (h.linktype = '1'
         OR abs(degrees(mod((h.az_end - r.az_end + 3 * pi())::numeric, (2 * pi())::numeric) - pi()::numeric)) > 40)
  ORDER BY t.seq LIMIT 1
)
SELECT a.pos, a.id AS link_id, (SELECT count(*) FROM xa) AS total,
       (a.source IN (SELECT start_vid FROM reach)) AS target,
       (SELECT string_agg(t.turn, ',') FROM turns t WHERE t.pos = a.pos) AS dirs,
       (SELECT link_id FROM conn) AS maneuver_link
FROM xa a ORDER BY a.pos;
