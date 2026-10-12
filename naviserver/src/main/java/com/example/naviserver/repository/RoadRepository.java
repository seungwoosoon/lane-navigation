package com.example.naviserver.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoadRepository {
    private final JdbcTemplate jdbcTemplate;
    public RoadRepository(JdbcTemplate jdbcTemplate){this.jdbcTemplate = jdbcTemplate;}

    public int countLanes(double lat, double lng) {
        String sql = """
                WITH RECURSIVE lanes AS (
                    SELECT id, laneno, r_linkid, l_linkid
                    FROM a2_link
                    WHERE id = (SELECT id FROM a2_link ORDER BY (geom<->ST_Transform(
                        ST_SetSRID( ST_MakePoint(?, ?), 4326 ),
                        5179
                    )) LIMIT 1)
                
                    UNION
                
                    SELECT nxt.id, nxt.laneno, nxt.r_linkid, nxt.l_linkid
                    FROM a2_link nxt
                    JOIN lanes ON nxt.id = lanes.r_linkid or nxt.id = lanes.l_linkid
                )
                SELECT COUNT(*) AS total_lanes FROM lanes
        """;
        return jdbcTemplate.queryForObject(sql, Integer.class, lng, lat);
    }
}
