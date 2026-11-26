package com.example.naviserver.dto;

import java.util.List;

/**
 * 재생할 주행 기록. sessionId 가 null 이면 서버에 재생할 기록이 없다.
 * @param recordingStartMs 녹화 시작 시각 (UTC ms, 카메라 시작 시각 중 가장 이른 값)
 */
public record ReplayTrack(String sessionId, long recordingStartMs, List<ReplayPoint> points) {

    /** @param timestampMs GPS 시각 (UTC ms), speed 는 m/s (없으면 null) */
    public record ReplayPoint(long timestampMs, double lat, double lng, Double speed) {}
}
