package com.example.naviserver.repository;

import com.example.naviserver.dto.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 정밀도로지도(a2_link)와 차로 그래프(route_edge)에서 차로 정보를 조회한다.
 * 차로 번호는 진행 방향 기준 왼쪽부터 1, 2, 3 ... (좌회전 포켓 차로 91~93도 포함해서 센다)
 */
@Repository
public class RoadRepository {

    /** 분기점 앞 단면의 차로 중 차선 변경 없이 분기점 뒤 도로로 이어지는 차로(목표 차로)와, 차로별 실제 진행 방향을 찾는다. */
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
            FROM xa a ORDER BY a.pos
            """;

    /** 분기점 앞에서 분기점 뒤까지 차로를 따라가며 허용하는 최대 거리(m) */
    private static final double MAX_FOLLOW_DISTANCE_M = 400;

    private final JdbcTemplate jdbcTemplate;

    public RoadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 진행 방향 표시 순서 (왼쪽 화살표부터) */
    private static final List<String> DIRECTION_ORDER = List.of("UTURN", "LEFT", "STRAIGHT", "RIGHT");

    /**
     * 분기점 차로 안내 정보 (차로 번호는 왼쪽부터 1번)
     * @param laneDirections 차로별 실제 진행 방향. 0번 = 1차로. 예) [[LEFT], [STRAIGHT], [STRAIGHT, RIGHT]]
     */
    public record LaneGuide(int totalLanes, List<Integer> targetLanes, List<List<String>> laneDirections) {

        public static final LaneGuide NONE = new LaneGuide(0, List.of(), List.of());
    }

    /**
     * @param beforePrev 분기점 앞 지점보다 조금 더 앞의 경로 점 (진행 방향 계산용)
     * @param before     분기점 앞 지점
     * @param afterPrev  분기점 뒤 지점보다 조금 앞의 경로 점 (진행 방향 계산용)
     * @param after      분기점 뒤 지점
     * @return 차로를 찾지 못하면 totalLanes = 0
     */
    public LaneGuide findLaneGuide(Point beforePrev, Point before, Point afterPrev, Point after) {
        List<Integer> targets = new ArrayList<>();
        List<List<String>> directions = new ArrayList<>();
        int[] total = {0};
        jdbcTemplate.query(TARGET_LANES_SQL, (RowCallbackHandler) rs -> {
                    total[0] = rs.getInt("total");
                    if (rs.getBoolean("target")) targets.add(rs.getInt("pos"));
                    directions.add(toDirections(rs.getString("dirs")));
                },
                before.lng(), before.lat(), beforePrev.lng(), beforePrev.lat(), before.lng(), before.lat(),
                after.lng(), after.lat(), afterPrev.lng(), afterPrev.lat(), after.lng(), after.lat(),
                MAX_FOLLOW_DISTANCE_M);
        return new LaneGuide(total[0], targets, directions);
    }

    /** "STRAIGHT,LEFT" → [LEFT, STRAIGHT] (교차로를 못 찾은 차로는 빈 목록) */
    private static List<String> toDirections(String dirs) {
        if (dirs == null) {
            return List.of();
        }
        List<String> list = new ArrayList<>(List.of(dirs.split(",")));
        list.sort(Comparator.comparingInt(DIRECTION_ORDER::indexOf));
        return list;
    }
}
