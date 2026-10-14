package com.example.naviserver.dto;

import java.util.List;

public record NavResponse(
        boolean guide,
        String maneuver,
        Integer distanceToBranch,
        Integer totalLanes,
        Integer currentLane,
        List<Integer> targetLanes,
        String message,
        boolean arrived
) {}
