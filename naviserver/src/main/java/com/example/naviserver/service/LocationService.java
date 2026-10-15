package com.example.naviserver.service;

import com.example.naviserver.dto.LocationRequest;
import com.example.naviserver.dto.LocationResponse;
import com.example.naviserver.repository.RoadRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

@Service
@ConditionalOnBean(DataSource.class)
public class LocationService {
    private final RoadRepository roadRepository;
    public LocationService(RoadRepository roadRepository){this.roadRepository = roadRepository;}

    public LocationResponse handle(LocationRequest response){
        int totalLanes = roadRepository.countLanes(response.lat(), response.lng());
        return new LocationResponse(false, totalLanes, List.of(1,2), 99);
    }
}
