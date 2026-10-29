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
    private static final double ARRIVED_DISTANCE_M = 30.0;   // 목적지 도착 판정 거리

    // 분기점 앞/뒤 단면을 잡을 경로상 거리(m). 앞쪽은 교차로 안에 걸리면 조금씩 더 멀리 잡아 다시 시도한다.
    private static final double[] BEFORE_DISTANCES_M = {30, 50, 70};
    private static final double AFTER_DISTANCE_M = 40;
    private static final double HEADING_REF_M = 10;          // 진행 방향 계산용 기준점 간격

    private final RoadRepository roadRepository;
    private final int placeholderCurrentLane;

    private final AtomicInteger routeIdCounter = new AtomicInteger(0);
    private List<Point> routePoints = List.of();
    private List<ManeuverInfo> maneuvers = List.of();
    private List<Integer> maneuverPathIdx = List.of();
    private final Map<Integer, LaneGuide> laneGuideCache = new HashMap<>();
    private int nextManeuverIdx = 0;
    private Point destination = null;

    public NavigationService(RoadRepository roadRepository,
                             @Value("${navi.placeholder-current-lane:2}") int placeholderCurrentLane) {
        this.roadRepository = roadRepository;
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
        this.destination = destination;
        return routeIdCounter.incrementAndGet();
    }

    /**
     * @param currentLane AI가 판단한 현재 차로(왼쪽부터 1번). AI 연동 전에는 null → 임시값 사용
     */
    public synchronized NavResponse processLocation(double lat, double lng, Integer currentLane) {
        if (destination != null && distanceMeters(lat, lng, destination.lat(), destination.lng()) < ARRIVED_DISTANCE_M) {
            return new NavResponse(false, null, null, null, null, null, null, "목적지에 도착했습니다.", true);
        }

        while (nextManeuverIdx < maneuvers.size()) {
            ManeuverInfo m = maneuvers.get(nextManeuverIdx);
            if (distanceMeters(lat, lng, m.lat(), m.lng()) < PASSED_DISTANCE_M) {
                nextManeuverIdx++;
            } else {
                break;
            }
        }

        if (nextManeuverIdx >= maneuvers.size()) {
            return new NavResponse(false, null, null, null, null, null, null, null, false);
        }

        ManeuverInfo next = maneuvers.get(nextManeuverIdx);
        double dist = distanceMeters(lat, lng, next.lat(), next.lng());
        int distInt = (int) dist;

        if (dist > TRIGGER_DISTANCE_M) {
            return new NavResponse(false, null, null, null, null, null, null, null, false);
        }

        // 정밀도로지도에서 전체 차로 수와 목표 차로 조회 (분기점마다 한 번만 계산)
        LaneGuide lanes = laneGuideCache.computeIfAbsent(nextManeuverIdx, this::computeLaneGuide);
        if (lanes.totalLanes() == 0) {
            // 정밀지도 범위 밖이거나 차로를 찾지 못한 경우: 회전 안내만 한다
            String message = distInt + "m 앞 " + maneuverKor(next.type()) + "입니다.";
            return new NavResponse(true, next.type(), distInt, null, null, List.of(), List.of(), message, false);
        }

        int total = lanes.totalLanes();
        int current = clamp(currentLane != null ? currentLane : placeholderCurrentLane, 1, total);
        String message = buildMessage(distInt, next.type(), current, lanes.targetLanes());
        return new NavResponse(true, next.type(), distInt, total, current, lanes.targetLanes(), lanes.laneDirections(), message, false);
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
                if (g.totalLanes() > 0 && !g.targetLanes().isEmpty()) {
                    return g;
                }
                if (best.totalLanes() == 0) {
                    best = g;
                }
            }
        } catch (Exception e) {
            log.warn("목표 차로 조회 실패 (maneuver {}): {}", maneuverIdx, e.getMessage());
        }
        return best;
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

    private String buildMessage(int dist, String type, int current, List<Integer> targetLanes) {
        String head = dist + "m 앞 " + maneuverKor(type) + ". ";
        if (targetLanes.isEmpty()) {
            return head + "현재 " + current + "차로입니다.";
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
