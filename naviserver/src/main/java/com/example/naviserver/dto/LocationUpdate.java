package com.example.naviserver.dto;

/**
 * 앱이 1초마다 보내는 위치.
 * @param currentLane 앱이 직접 아는 현재 차로(왼쪽부터 1번). 보통 null → 서버가 Lane AI에 묻는다
 * @param timestampMs GPS 시각(UTC ms). 주행 기록 재생 모드에서 Lane AI 재생 시각을 맞추는 데 쓴다 (없어도 됨)
 */
public record LocationUpdate(int routeId, double lat, double lng, double speed, Integer currentLane, Long timestampMs) {}
