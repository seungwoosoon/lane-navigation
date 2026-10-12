package com.example.naviserver.dto;

import java.util.List;

public record RouteResponse(int routeId, List<Point> path) {
}