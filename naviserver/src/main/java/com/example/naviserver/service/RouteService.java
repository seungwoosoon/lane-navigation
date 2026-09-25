package com.example.naviserver.service;

import com.example.naviserver.dto.Point;
import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RouteService {

    private static final int POINT_COUNT = 20;

    public RouteResponse findRoute(RouteRequest request) {
        List<Point> path = new ArrayList<>();

        for (int i = 0; i < POINT_COUNT; i++) {
            double ratio = i / (double) (POINT_COUNT - 1);   // 0.0 ~ 1.0

            double lat = request.startLat() + (request.endLat() - request.startLat()) * ratio;
            double lng = request.startLng() + (request.endLng() - request.startLng()) * ratio;

            path.add(new Point(lat, lng));
        }

        return new RouteResponse(path);
    }
}