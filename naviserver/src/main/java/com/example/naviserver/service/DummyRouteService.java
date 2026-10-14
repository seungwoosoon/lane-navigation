package com.example.naviserver.service;

import com.example.naviserver.dto.Point;
import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;

import java.util.ArrayList;
import java.util.List;

public class DummyRouteService implements RouteService {

    private static final int POINT_COUNT = 20;

    @Override
    public RouteResponse findRoute(RouteRequest request) {
        List<Point> path = new ArrayList<>();

        for (int i = 0; i < POINT_COUNT; i++) {
            double ratio = i / (double) (POINT_COUNT - 1);
            double lat = request.start().lat() + (request.end().lat() - request.start().lat()) * ratio;
            double lng = request.start().lng() + (request.end().lng() - request.start().lng()) * ratio;
            path.add(new Point(lat, lng));
        }

        return new RouteResponse(1, path);
    }
}
