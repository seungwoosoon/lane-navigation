package com.example.naviserver.dto;

import java.util.List;

/**
 * @param currentLane       현재 차로 (왼쪽부터 1번). 모르면 null → 앱은 이동 안내 없이 목표 차로만 보여준다
 * @param currentLaneSource 현재 차로를 어디서 얻었는지: "AI", "APP", "PLACEHOLDER", 모르면 null
 */
public record NavResponse(
        boolean guide,
        String maneuver,
        Integer distanceToBranch,
        Integer totalLanes,
        Integer currentLane,
        List<Integer> targetLanes,
        List<List<String>> laneDirections,   // 차로별 실제 진행 방향 (0번 = 1차로)
        String message,
        boolean arrived,
        String currentLaneSource
) {}
