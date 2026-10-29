package com.example.naviserver.repository;

import com.example.naviserver.dto.Point;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class RouteRepository {
    private final Map<Integer, List<Point>> map = new ConcurrentHashMap<>();

    public void saveRoute(int id, List<Point> path) {
        map.put(id, path);
    }

    public void deleteRoute(int id) {
        map.remove(id);
    }

    public Optional<List<Point>> findRouteById(int id) {
        return Optional.ofNullable(map.get(id));
    }
}
