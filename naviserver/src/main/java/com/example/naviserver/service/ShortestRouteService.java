package com.example.naviserver.service;

import com.example.naviserver.dto.Point;
import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;
import com.example.naviserver.repository.RouteRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Primary
public class ShortestRouteService implements RouteService {

    private static final String SQL = """
            SELECT ST_Y(p.geom) AS lat, ST_X(p.geom) AS lng
            FROM pgr_dijkstra(
                'SELECT id, source, target, cost, reverse_cost FROM route_edge',
                (SELECT source FROM route_edge WHERE link_id <> 'LC'
                 ORDER BY geom <-> ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179) LIMIT 1),
                (SELECT target FROM route_edge WHERE link_id <> 'LC'
                 ORDER BY geom <-> ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179) LIMIT 1),
                directed := true
            ) d
            JOIN route_edge e ON d.edge = e.id
            CROSS JOIN LATERAL ST_DumpPoints(ST_Transform(ST_Force2D(e.geom), 4326)) p
            WHERE e.link_id <> 'LC'
            ORDER BY d.seq, p.path
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RouteRepository routeRepository;
    private final AtomicInteger sequence = new AtomicInteger(0);

    public ShortestRouteService(JdbcTemplate jdbcTemplate, RouteRepository routeRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.routeRepository = routeRepository;
    }

    @Override
    public RouteResponse findRoute(RouteRequest request) {
        List<Point> path = jdbcTemplate.query(
                SQL,
                (rs, rowNum) -> new Point(rs.getDouble("lat"), rs.getDouble("lng")),
                request.start().lng(), request.start().lat(),
                request.end().lng(), request.end().lat()
        );

        int routeId = sequence.incrementAndGet();
        routeRepository.saveRoute(routeId, path);
        return new RouteResponse(routeId, path);
    }
}