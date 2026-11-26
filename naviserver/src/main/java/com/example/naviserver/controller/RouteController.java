package com.example.naviserver.controller;

import com.example.naviserver.dto.LocationUpdate;
import com.example.naviserver.dto.NavResponse;
import com.example.naviserver.dto.ReplayTrack;
import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;
import com.example.naviserver.service.NavigationService;
import com.example.naviserver.service.ReplaySession;
import com.example.naviserver.service.RouteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RouteController {

    private final RouteService routeService;
    private final NavigationService navigationService;
    private final ReplaySession replaySession;

    public RouteController(RouteService routeService, NavigationService navigationService, ReplaySession replaySession) {
        this.routeService = routeService;
        this.navigationService = navigationService;
        this.replaySession = replaySession;
    }

    @PostMapping("/route")
    public RouteResponse route(@RequestBody RouteRequest request) {
        return routeService.findRoute(request);
    }

    @PostMapping("/location")
    public NavResponse location(@RequestBody LocationUpdate update) {
        return navigationService.processLocation(update.lat(), update.lng(), update.currentLane(), update.timestampMs());
    }

    /** 주행 기록 재생용 GPS. 서버에 재생할 기록이 없으면 sessionId = null, points = [] */
    @GetMapping("/replay")
    public ReplayTrack replay() {
        return replaySession.track();
    }
}
