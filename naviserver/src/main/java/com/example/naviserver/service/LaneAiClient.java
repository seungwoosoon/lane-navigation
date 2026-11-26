package com.example.naviserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lane AI(lane-ai/scripts/serve_lane_replay.py)에 현재 차로를 묻는다.
 * 명세: lane-ai/docs/server-replay-integration.md
 * AI가 꺼져 있거나 답을 못 하면 null 을 돌려주고, 안내는 현재 차로 없이 계속한다.
 */
@Service
public class LaneAiClient {

    private static final Logger log = LoggerFactory.getLogger(LaneAiClient.class);
    private static final long RETRY_AFTER_FAILURE_MS = 10_000;

    /** AI 응답. status 가 VALID 일 때만 currentLane, mapLinkId 를 믿는다. */
    public record Estimate(String status, Integer currentLane, String mapLinkId, Integer targetLane,
                           String movement, Integer laneChanges, String laneNumbering, String reason) {
        public boolean valid() {
            return "VALID".equals(status);
        }
    }

    private final boolean enabled;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> reportedMismatch = new HashSet<>();
    private long downUntil = 0;

    public LaneAiClient(@Value("${lane-ai.enabled:true}") boolean enabled,
                        @Value("${lane-ai.base-url:http://127.0.0.1:8765}") String baseUrl,
                        @Value("${lane-ai.timeout-ms:500}") int timeoutMs) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 녹화 재생 시각의 차로 추정을 받는다.
     * @return AI가 꺼져 있거나, 연결이 안 되거나, 이 회전 지점 자료가 없으면 null
     */
    public synchronized Estimate replayEstimate(String sessionId, String maneuverLinkId, long recordingElapsedMs) {
        if (!enabled || sessionId == null || maneuverLinkId == null || recordingElapsedMs < 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now < downUntil) {
            return null;
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "sessionId", sessionId,
                    "maneuverLinkId", maneuverLinkId,
                    "recordingElapsedMs", recordingElapsedMs));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/replay/lane-estimate"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                // AI 재생 자료가 다른 세션/회전 지점용이다. 이 회전 지점은 AI 없이 안내한다.
                if (reportedMismatch.add(sessionId + "/" + maneuverLinkId)) {
                    log.info("Lane AI 재생 자료에 없는 회전 지점: session={}, maneuverLinkId={}", sessionId, maneuverLinkId);
                }
                return null;
            }
            if (response.statusCode() != 200) {
                log.warn("Lane AI 응답 오류 {}: {}", response.statusCode(), response.body());
                return null;
            }
            JsonNode n = mapper.readTree(response.body());
            return new Estimate(text(n, "status"), integer(n, "currentLane"), text(n, "mapLinkId"),
                    integer(n, "targetLane"), text(n, "movement"), integer(n, "laneChanges"),
                    text(n, "laneNumbering"), text(n, "reason"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            downUntil = now + RETRY_AFTER_FAILURE_MS;
            log.warn("Lane AI 연결 실패 ({}), {}초 뒤 다시 시도: {}", baseUrl, RETRY_AFTER_FAILURE_MS / 1000, e.toString());
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.canConvertToInt() ? null : v.asInt();
    }
}
