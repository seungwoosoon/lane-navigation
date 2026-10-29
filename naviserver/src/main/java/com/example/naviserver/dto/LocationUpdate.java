package com.example.naviserver.dto;

/**
 * @param currentLane AI가 판단한 현재 차로(왼쪽부터 1번). 아직 AI 연동 전이라 보통 null
 */
public record LocationUpdate(int routeId, double lat, double lng, double speed, Integer currentLane) {}
