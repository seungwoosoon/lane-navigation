-- =========================================================
-- 분기점 목표 차로 계산 (RoadRepository.TARGET_LANES_SQL 과 같은 내용)
-- ? 순서: 분기점 앞 지점(lng, lat), 그보다 조금 앞 점(lng, lat), 분기점 앞 지점(lng, lat),
--         분기점 뒤 지점(lng, lat), 그보다 조금 앞 점(lng, lat), 분기점 뒤 지점(lng, lat),
--         차로를 따라갈 최대 거리(m)
-- 결과: pos(왼쪽부터 차로 번호), total(전체 차로 수), target(목표 차로 여부)
-- 방법: 1) 분기점 앞/뒤 지점에서 진행 방향이 같은 가장 가까운 차로를 찾고
--       2) l_linkid / r_linkid 로 그 단면의 모든 차로를 모아 왼쪽부터 번호를 매긴 뒤
--       3) 차선 변경 엣지(LC)를 뺀 그래프에서 분기점 뒤 도로까지 이어지는 차로 = 목표 차로
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
)
SELECT a.pos, (SELECT count(*) FROM xa) AS total, (a.source IN (SELECT start_vid FROM reach)) AS target
FROM xa a ORDER BY a.pos;
