package com.example.naviserver.service;

import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;

public interface RouteService {
    public RouteResponse findRoute(RouteRequest request);
}
