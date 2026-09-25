package com.example.naviserver.controller;

import com.example.naviserver.service.RouteService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api")
public class RouteController {
    private final RouteService routeService;

}
