package com.example.naviserver.dto;

import java.util.List;

public record NavResponse(
        boolean guide,
        String maneuver,
        Integer distanceToBranch,
        Integer totalLanes,
        Integer currentLane,
        List<Integer> targetLanes,
        List<List<String>> laneDirections,   // 차로별 실제 진행 방향 (0번 = 1차로)
        String message,
        boolean arrived
) {}
