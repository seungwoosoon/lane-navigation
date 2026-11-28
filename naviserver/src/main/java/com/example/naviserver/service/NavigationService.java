package com.example.naviserver.service;

import com.example.naviserver.dto.NavResponse;
import com.example.naviserver.dto.Point;
import com.example.naviserver.repository.RoadRepository;
import com.example.naviserver.repository.RoadRepository.LaneGuide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NavigationService {

    private static final Logger log = LoggerFactory.getLogger(NavigationService.class);

    private static final double TRIGGER_DISTANCE_M = 300.0;  // 분기점 안내 시작 거리 (AI 연동 후 속도 기반으로 변경 예정)
    private static final double PASSED_DISTANCE_M = 20.0;    // 이 거리 안으로 들어오면 분기점 통과로 본다
    // 카카오 회전 지점은 실제로 도는 자리와 수십 m 어긋날 수 있다 (11/24 유턴: 약 50m 뒤).
    // 그래서 이 거리 안까지 왔다가, 가장 가까웠던 거리보다 PASS_AWAY_M 이상 멀어져도 통과로 본다.
    private static final double PASS_NEAR_M = 100.0;
    private static final double PASS_AWAY_M = 25.0;
    private static final double ARRIVED_DISTANCE_M = 30.0;   // 목적지 도착 판정 거리

    // 분기점 앞 단면을 잡을 경로상 거리(m). 여러 곳에서 재 보고 차로가 가장 많은 단면을 쓴다.
    // (교차로 바로 앞은 도류화 우회전 차로가 이미 갈라져 나가 차로 수가 줄어 있을 수 있다)
    private static final double[] BEFORE_DISTANCES_M = {30, 50, 70};
    private static final double AFTER_DISTANCE_M = 40;
    private static final double HEADING_REF_M = 10;          // 진행 방향 계산용 기준점 간격

    private final RoadRepository roadRepository;
    private final LaneAiClient laneAi;
    private final ReplaySession replay;
    private final int placeholderCurrentLane;

    private final AtomicInteger routeIdCounter = new AtomicInteger(0);
    private List<Point> routePoints = List.of();
    private List<ManeuverInfo> maneuvers = List.of();
    private List<Integer> maneuverPathIdx = List.of();
    private final Map<Integer, LaneGuide> laneGuideCache = new HashMap<>();
    private int nextManeuverIdx = 0;
    private double closestToNextM = Double.MAX_VALUE;  // 다음 분기점에 가장 가까웠던 거리
    private Point destination = null;

    // AI가 마지막으로 알려준 차로. AI가 잠깐 모를 때(분석 구간 끝, 일시 오류) 가까운 거리 안에서는 이 값을 이어 쓴다
    private static final double AI_HOLD_DISTANCE_M = 80.0;  // 차로를 바꾸려면 보통 수십 m를 달려야 한다
    private Integer lastAiLane = null;
    private int lastAiManeuverIdx = -1;
    private Point lastAiPoint = null;

    public NavigationService(RoadRepository roadRepository, LaneAiClient laneAi, ReplaySession replay,
                             @Value("${navi.placeholder-current-lane:0}") int placeholderCurrentLane) {
        this.roadRepository = roadRepository;
        this.laneAi = laneAi;
        this.replay = replay;
        this.placeholderCurrentLane = placeholderCurrentLane;
    }

    public synchronized int setRoute(List<Point> points, List<ManeuverInfo> maneuvers, Point destination) {
        this.routePoints = new ArrayList<>(points);
        this.maneuvers = new ArrayList<>(maneuvers);
        this.maneuverPathIdx = new ArrayList<>();
        for (ManeuverInfo m : maneuvers) {
            maneuverPathIdx.add(nearestPathIndex(m.lat(), m.lng()));
        }
        this.laneGuideCache.clear();
        this.nextManeuverIdx = 0;
        this.closestToNextM = Double.MAX_VALUE;
        this.destination = destination;
        this.lastAiLane = null;
        this.lastAiManeuverIdx = -1;
        this.lastAiPoint = null;
        return routeIdCounter.incrementAndGet();
    }

    /**
     * @param appCurrentLane 앱이 직접 보낸 현재 차로(왼쪽부터 1번). 보통 null → Lane AI에 묻는다
     * @param timestampMs    GPS 시각(UTC ms). 주행 기록 재생 때 Lane AI 재생 시각을 계산하는 데 쓴다
     */
    public synchronized NavResponse processLocation(double lat, double lng, Integer appCurrentLane, Long timestampMs) {
        while (nextManeuverIdx < maneuvers.size()) {
            ManeuverInfo m = maneuvers.get(nextManeuverIdx);
            double d = distanceMeters(lat, lng, m.lat(), m.lng());
            closestToNextM = Math.min(closestToNextM, d);
            boolean passed = d < PASSED_DISTANCE_M
                    || (closestToNextM < PASS_NEAR_M && d > closestToNextM + PASS_AWAY_M);
            if (passed) {
                nextManeuverIdx++;
                closestToNextM = Double.MAX_VALUE;
            } else {
                break;
            }
        }

        // 도착: 회전 지점을 모두 지난 뒤 목적지 근처에 오면 (유턴 경로는 목적지가 반대 차로라 진입 중에도 가까울 수 있다)
        if (nextManeuverIdx >= maneuvers.size()) {
            boolean arrived = destination != null
                    && distanceMeters(lat, lng, destination.lat(), destination.lng()) < ARRIVED_DISTANCE_M;
            return new NavResponse(false, null, null, null, null, null, null, arrived ? "목적지에 도착했습니다." : null,
                    arrived, null);
        }

        ManeuverInfo next = maneuvers.get(nextManeuverIdx);
        double dist = distanceMeters(lat, lng, next.lat(), next.lng());
        int distInt = (int) dist;

        if (dist > TRIGGER_DISTANCE_M) {
            return new NavResponse(false, null, null, null, null, null, null, null, false, null);
        }

        // 정밀도로지도에서 전체 차로 수와 목표 차로 조회 (분기점마다 한 번만 계산)
        LaneGuide lanes = laneGuideCache.computeIfAbsent(nextManeuverIdx, this::computeLaneGuide);
        if (lanes.totalLanes() == 0) {
            // 정밀지도 범위 밖이거나 차로를 찾지 못한 경우: 회전 안내만 한다
            String message = distInt + "m 앞 " + maneuverKor(next.type()) + "입니다.";
            return new NavResponse(true, next.type(), distInt, null, null, List.of(), List.of(), message, false, null);
        }

        int total = lanes.totalLanes();
        Integer current = null;
        String source = null;
        if (appCurrentLane != null) {
            current = clamp(appCurrentLane, 1, total);
            source = "APP";
        } else {
            current = currentLaneFromAi(lanes, timestampMs);
            if (current != null) {
                lastAiLane = current;
                lastAiManeuverIdx = nextManeuverIdx;
                lastAiPoint = new Point(lat, lng);
            } else if (lastAiLane != null && lastAiManeuverIdx == nextManeuverIdx
                    && distanceMeters(lat, lng, lastAiPoint.lat(), lastAiPoint.lng()) < AI_HOLD_DISTANCE_M) {
                current = lastAiLane;
            }
            if (current != null) {
                source = "AI";
            } else if (placeholderCurrentLane > 0) {
                current = clamp(placeholderCurrentLane, 1, total);
                source = "PLACEHOLDER";
            }
        }
        String message = buildMessage(distInt, next.type(), current, lanes.targetLanes());
        return new NavResponse(true, next.type(), distInt, total, current, lanes.targetLanes(), lanes.laneDirections(),
                message, false, source);
    }

    /** 이전 버전 호환: 시각 없이 호출 */
    public NavResponse processLocation(double lat, double lng, Integer appCurrentLane) {
        return processLocation(lat, lng, appCurrentLane, null);
    }

    // ------------------------------------------------------------------ Lane AI

    /**
     * Lane AI에게 현재 차로를 묻고, 서버의 차로 번호(분기점 앞 단면, 왼쪽부터)로 바꾼다.
     * AI는 현재 달리는 차로의 정밀지도 링크(mapLinkId)를 주므로, 그 링크가 이어지는 단면 차로를 찾는다.
     * @return 모르면 null (안내는 목표 차로만 한다)
     */
    private Integer currentLaneFromAi(LaneGuide lanes, Long timestampMs) {
        long elapsed = replay.elapsedMs(timestampMs);
        if (elapsed < 0 || lanes.maneuverLinkId() == null) {
            return null;
        }
        LaneAiClient.Estimate e = laneAi.replayEstimate(replay.sessionId(), lanes.maneuverLinkId(), elapsed);
        if (e == null || !e.valid()) {
            return null;
        }
        try {
            Integer pos = roadRepository.findLanePosition(e.mapLinkId(), lanes.laneLinkIds());
            if (pos != null) {
                return pos;
            }
        } catch (Exception ex) {
            log.warn("AI 차로 링크 대응 실패 ({}): {}", e.mapLinkId(), ex.getMessage());
        }
        // 링크로 못 찾으면, 화면 기준 번호(physical)이고 차로 수 안이면 AI 번호를 그대로 쓴다
        if ("physical".equals(e.laneNumbering()) && e.currentLane() != null
                && e.currentLane() >= 1 && e.currentLane() <= lanes.totalLanes()) {
            return e.currentLane();
        }
        return null;
    }

    // ------------------------------------------------------------------ 목표 차로 계산

    private LaneGuide computeLaneGuide(int maneuverIdx) {
        int pathIdx = maneuverPathIdx.get(maneuverIdx);
        Point after = pointAlong(pathIdx, AFTER_DISTANCE_M, +1);
        Point afterPrev = pointAlong(pathIdx, AFTER_DISTANCE_M - HEADING_REF_M, +1);
        LaneGuide best = LaneGuide.NONE;
        try {
            for (double beforeM : BEFORE_DISTANCES_M) {
                Point before = pointAlong(pathIdx, beforeM, -1);
                Point beforePrev = pointAlong(pathIdx, beforeM + HEADING_REF_M, -1);
                LaneGuide g = roadRepository.findLaneGuide(beforePrev, before, afterPrev, after);
                if (isBetter(g, best)) {
                    best = g;
                }
            }
        } catch (Exception e) {
            log.warn("목표 차로 조회 실패 (maneuver {}): {}", maneuverIdx, e.getMessage());
        }
        return best;
    }

    /** 목표 차로를 찾은 단면이 우선, 그다음은 차로가 더 많은 단면 (같으면 분기점에 가까운 앞의 것) */
    private static boolean isBetter(LaneGuide g, LaneGuide best) {
        boolean gHasTarget = !g.targetLanes().isEmpty();
        boolean bestHasTarget = !best.targetLanes().isEmpty();
        if (gHasTarget != bestHasTarget) {
            return gHasTarget;
        }
        return g.totalLanes() > best.totalLanes();
    }

    /** 경로에서 pathIdx 지점부터 distance(m)만큼 앞(+1) 또는 뒤(-1)로 간 지점 */
    private Point pointAlong(int pathIdx, double distance, int step) {
        if (routePoints.isEmpty()) {
            return new Point(0, 0);
        }
        double acc = 0;
        int i = pathIdx;
        while (i + step >= 0 && i + step < routePoints.size()) {
            Point a = routePoints.get(i);
            Point b = routePoints.get(i + step);
            double seg = distanceMeters(a.lat(), a.lng(), b.lat(), b.lng());
            if (seg > 0 && acc + seg >= distance) {
                double t = (distance - acc) / seg;
                return new Point(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t);
            }
            acc += seg;
            i += step;
        }
        return routePoints.get(i);
    }

    private int nearestPathIndex(double lat, double lng) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < routePoints.size(); i++) {
            Point p = routePoints.get(i);
            double d = distanceMeters(lat, lng, p.lat(), p.lng());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 안내 문구

    private String buildMessage(int dist, String type, Integer current, List<Integer> targetLanes) {
        String head = dist + "m 앞 " + maneuverKor(type) + ". ";
        if (targetLanes.isEmpty()) {
            return current == null ? head.trim() : head + "현재 " + current + "차로입니다.";
        }
        if (current == null) {
            // 현재 차로를 모르면 갈 차로만 알려준다
            StringBuilder lanesText = new StringBuilder();
            for (int i = 0; i < targetLanes.size(); i++) {
                lanesText.append(i == 0 ? "" : ", ").append(targetLanes.get(i));
            }
            return head + lanesText + "차로로 가세요.";
        }
        if (targetLanes.contains(current)) {
            return head + "현재 차로를 유지하세요.";
        }
        int nearest = targetLanes.get(0);
        for (int t : targetLanes) {
            if (Math.abs(t - current) < Math.abs(nearest - current)) {
                nearest = t;
            }
        }
        int shift = nearest - current;
        String dir = shift < 0 ? "왼쪽" : "오른쪽";
        return head + "현재 " + current + "차로, " + dir + "으로 " + Math.abs(shift) + "차로 이동하세요.";
    }

    private String maneuverKor(String type) {
        return switch (type) {
            case "LEFT" -> "좌회전";
            case "RIGHT" -> "우회전";
            case "UTURN" -> "유턴";
            default -> type;
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
