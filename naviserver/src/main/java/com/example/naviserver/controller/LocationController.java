package com.example.naviserver.controller;

import com.example.naviserver.dto.LocationRequest;
import com.example.naviserver.dto.LocationResponse;
import com.example.naviserver.service.LocationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;

@RestController
@RequestMapping("/api")
@ConditionalOnBean(DataSource.class)
public class LocationController {
    private final LocationService locationService;
    public LocationController(LocationService locationService){this.locationService = locationService;}

    @PostMapping("/location")
    public LocationResponse respondLocation(@RequestBody LocationRequest locationRequest){
        return locationService.handle(locationRequest);
    }

}
