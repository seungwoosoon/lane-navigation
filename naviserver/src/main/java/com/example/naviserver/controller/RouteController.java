package com.example.naviserver.controller;

import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;
import com.example.naviserver.service.RouteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RouteController {
    private final RouteService routeService;
    public RouteController(RouteService routeService) {   // 괄호로 받기
        this.routeService = routeService;                  // 받은 걸 담기
    }

    @PostMapping("/route")       // "이 출발지·도착지로 경로 만들어줘" → 좌표 4개를 보냄
    public RouteResponse route(@RequestBody RouteRequest request) {
        return routeService.findRoute(request);
    }

}
