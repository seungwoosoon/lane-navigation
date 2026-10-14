-- =========================================================
-- 정밀도로지도(a2_link) → pgRouting 길찾기 그래프 만들기
-- 필요: PostGIS, pgRouting (CREATE EXTENSION pgrouting;)
-- 여러 번 실행해도 되도록 DROP 후 다시 만든다
-- =========================================================

-- 1. 노드 번호표: 글자 노드 id → 정수 vid
DROP TABLE IF EXISTS route_edge;
DROP TABLE IF EXISTS route_vertex;

CREATE TABLE route_vertex AS
SELECT row_number() OVER () AS vid, node_id
FROM (
    SELECT fromnodeid AS node_id FROM a2_link
    UNION
    SELECT tonodeid   AS node_id FROM a2_link
) t;
-- 확인: SELECT count(*) FROM route_vertex;  → 3254


-- 2. 엣지: 링크 하나 = 한 방향 엣지 하나 (reverse_cost -1 = 역방향 불가)
CREATE TABLE route_edge AS
SELECT row_number() OVER () AS id,
       e.id          AS link_id,
       fv.vid        AS source,
       tv.vid        AS target,
       e.length      AS cost,
       -1            AS reverse_cost,
       e.geom        AS geom
FROM a2_link e
JOIN route_vertex fv ON e.fromnodeid = fv.node_id
JOIN route_vertex tv ON e.tonodeid   = tv.node_id;
-- 확인: SELECT count(*) FROM route_edge;  → 3410


-- 3. 차선 변경 엣지(LC): 옆 차로 링크의 시작 노드끼리 양방향 연결
--    교차로 내부(linktype '1')는 차선 변경 금지, 비용 10
INSERT INTO route_edge (id, link_id, source, target, cost, reverse_cost, geom)
SELECT (SELECT max(id) FROM route_edge) + row_number() OVER (),
       'LC',
       ea.source,
       eb.source,
       10,
       10,
       NULL
FROM a2_link a
JOIN route_edge ea ON ea.link_id = a.id
JOIN route_edge eb ON eb.link_id = a.r_linkid
WHERE a.linktype <> '1';
-- 확인: SELECT count(*) FROM route_edge WHERE link_id = 'LC';  → 1435


-- 4. 테스트: 16호선 2차로 → 옆 1차로 (agg_cost 319.87, LC 1번)
-- SELECT d.seq, e.link_id, d.cost, d.agg_cost
-- FROM pgr_dijkstra(
--     'SELECT id, source, target, cost, reverse_cost FROM route_edge',
--     (SELECT source FROM route_edge WHERE link_id = 'A223AA310481'),
--     (SELECT target FROM route_edge WHERE link_id = 'A223AA311021'),
--     directed := true
-- ) d
-- LEFT JOIN route_edge e ON d.edge = e.id;
