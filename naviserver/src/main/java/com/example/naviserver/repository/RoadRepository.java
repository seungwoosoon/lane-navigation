package com.example.naviserver.repository;

import com.example.naviserver.dto.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 정밀도로지도(a2_link)와 차로 그래프(route_edge)에서 차로 정보를 조회한다.
 * 차로 번호는 진행 방향 기준 왼쪽부터 1, 2, 3 ... (좌회전 포켓 차로 91~93도 포함해서 센다)
 */
@Repository
public class RoadRepository {

    /** 분기점 앞 단면의 차로 중, 차선 변경 없이 분기점 뒤 도로로 이어지는 차로를 찾는다. */
    private static final String TARGET_LANES_SQL = """
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
            FROM xa a ORDER BY a.pos
            """;

    /** 분기점 앞에서 분기점 뒤까지 차로를 따라가며 허용하는 최대 거리(m) */
    private static final double MAX_FOLLOW_DISTANCE_M = 400;

    private final JdbcTemplate jdbcTemplate;

    public RoadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 분기점 차로 안내 정보: 전체 차로 수와 목표 차로(왼쪽부터 1번) */
    public record LaneGuide(int totalLanes, List<Integer> targetLanes) {}

    /**
     * @param beforePrev 분기점 앞 지점보다 조금 더 앞의 경로 점 (진행 방향 계산용)
     * @param before     분기점 앞 지점
     * @param afterPrev  분기점 뒤 지점보다 조금 앞의 경로 점 (진행 방향 계산용)
     * @param after      분기점 뒤 지점
     * @return 차로를 찾지 못하면 totalLanes = 0
     */
    public LaneGuide findLaneGuide(Point beforePrev, Point before, Point afterPrev, Point after) {
        List<Integer> targets = new ArrayList<>();
        int[] total = {0};
        jdbcTemplate.query(TARGET_LANES_SQL, (RowCallbackHandler) rs -> {
                    total[0] = rs.getInt("total");
                    if (rs.getBoolean("target")) targets.add(rs.getInt("pos"));
                },
                before.lng(), before.lat(), beforePrev.lng(), beforePrev.lat(), before.lng(), before.lat(),
                after.lng(), after.lat(), afterPrev.lng(), afterPrev.lat(), after.lng(), after.lat(),
                MAX_FOLLOW_DISTANCE_M);
        return new LaneGuide(total[0], targets);
    }

    /** 현재 위치에서 가장 가까운 차로가 속한 도로의 전체 차로 수 */
    public int countLanes(double lat, double lng) {
        String sql = """
                WITH RECURSIVE lanes AS (
                    SELECT id, laneno, r_linkid, l_linkid
                    FROM a2_link
                    WHERE id = (SELECT id FROM a2_link ORDER BY (geom <-> ST_Transform(
                        ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179)) LIMIT 1)
                    UNION
                    SELECT nxt.id, nxt.laneno, nxt.r_linkid, nxt.l_linkid
                    FROM a2_link nxt
                    JOIN lanes ON nxt.id = lanes.r_linkid OR nxt.id = lanes.l_linkid
                )
                SELECT COUNT(*) AS total_lanes FROM lanes
                """;
        return jdbcTemplate.queryForObject(sql, Integer.class, lng, lat);
    }
}
