package com.example.naviserver.service;

import com.example.naviserver.dto.ReplayTrack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 녹화한 주행 세션(recording.json + gps.csv)을 읽어 둔다.
 * - 앱의 재생 모드가 GET /api/replay 로 GPS 기록을 받아 실제 위치 대신 보낸다.
 * - 위치마다 온 GPS 시각에서 녹화 시작 시각을 빼서 Lane AI 재생 시각(recordingElapsedMs)을 만든다.
 * 폴더 구조는 lane-ai/scripts/prepare_gps.py 가 만드는 세션 폴더와 같다.
 */
@Service
public class ReplaySession {

    private static final Logger log = LoggerFactory.getLogger(ReplaySession.class);

    private final String sessionId;
    private final long recordingStartMs;
    private final List<ReplayTrack.ReplayPoint> points;

    public ReplaySession(@Value("${navi.replay.session-dir:}") String sessionDir) {
        String id = null;
        long start = -1;
        List<ReplayTrack.ReplayPoint> pts = List.of();
        if (sessionDir != null && !sessionDir.isBlank()) {
            try {
                Path dir = Path.of(sessionDir);
                JsonNode rec = new ObjectMapper().readTree(Files.readString(dir.resolve("recording.json"), StandardCharsets.UTF_8)
                        .replace("﻿", ""));
                id = rec.path("session_id").asText(null);
                for (JsonNode cam : rec.path("cameras")) {
                    if (cam.hasNonNull("start_timestamp_ms")) {
                        long t = cam.get("start_timestamp_ms").asLong();
                        start = start < 0 ? t : Math.min(start, t);
                    }
                }
                pts = readGps(dir.resolve(rec.path("gps_path").asText("gps.csv")));
                log.info("주행 기록 재생 준비: session={}, GPS {}개, 녹화 시작 {}", id, pts.size(), start);
            } catch (Exception e) {
                log.warn("주행 기록을 읽지 못했습니다 ({}): {}", sessionDir, e.toString());
                id = null;
                pts = List.of();
            }
        }
        this.sessionId = id;
        this.recordingStartMs = start;
        this.points = pts;
    }

    public boolean isEnabled() {
        return sessionId != null && !points.isEmpty();
    }

    public String sessionId() {
        return sessionId;
    }

    public ReplayTrack track() {
        return new ReplayTrack(sessionId, recordingStartMs, points);
    }

    /** GPS 시각(UTC ms) → 녹화 시작부터 흐른 ms. 재생 중이 아니거나 시작 시각을 모르면 -1 */
    public long elapsedMs(Long gpsTimestampMs) {
        if (!isEnabled() || recordingStartMs < 0 || gpsTimestampMs == null) {
            return -1;
        }
        return gpsTimestampMs - recordingStartMs;
    }

    /** gps.csv: timestamp_ms, latitude, longitude, speed_mps, bearing_deg (빈 값 허용) */
    private static List<ReplayTrack.ReplayPoint> readGps(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> header = Arrays.asList(lines.get(0).replace("﻿", "").trim().split(","));
        int t = header.indexOf("timestamp_ms");
        int lat = header.indexOf("latitude");
        int lng = header.indexOf("longitude");
        int spd = header.indexOf("speed_mps");
        if (t < 0 || lat < 0 || lng < 0) {
            throw new IOException("gps.csv 에 timestamp_ms, latitude, longitude 열이 필요합니다");
        }
        List<ReplayTrack.ReplayPoint> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if (f.length <= Math.max(t, Math.max(lat, lng)) || f[lat].isBlank() || f[lng].isBlank()) {
                continue;
            }
            Double speed = spd >= 0 && spd < f.length && !f[spd].isBlank() ? Double.valueOf(f[spd]) : null;
            out.add(new ReplayTrack.ReplayPoint(Long.parseLong(f[t].trim()), Double.parseDouble(f[lat]),
                    Double.parseDouble(f[lng]), speed));
        }
        return List.copyOf(out);
    }
}
