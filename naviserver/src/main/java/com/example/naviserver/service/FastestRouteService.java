package com.example.naviserver.service;

import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class FastestRouteService implements RouteService{

    private final AtomicInteger sequence = new AtomicInteger(0);
    @Override
    public RouteResponse findRoute(RouteRequest request) {
        return null;
    }
}
