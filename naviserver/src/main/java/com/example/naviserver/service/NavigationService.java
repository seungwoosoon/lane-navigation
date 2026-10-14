package com.example.naviserver.service;

import com.example.naviserver.dto.NavResponse;
import com.example.naviserver.dto.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class NavigationService {

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger routeIdCounter = new AtomicInteger(0);

    private int currentRouteId = 0;
    private List<Point> routePoints = List.of();
    private List<ManeuverInfo> maneuvers = List.of();
    private int nextManeuverIdx = 0;
    private Point destination = null;

    private static final double TRIGGER_DISTANCE_M = 300.0;
    private static final double PASSED_DISTANCE_M = 20.0;
    private static final double ARRIVED_DISTANCE_M = 30.0;

    public int setRoute(List<Point> points, List<ManeuverInfo> maneuvers, Point destination) {
        this.routePoints = new ArrayList<>(points);
        this.maneuvers = new ArrayList<>(maneuvers);
        this.nextManeuverIdx = 0;
        this.destination = destination;
        this.currentRouteId = routeIdCounter.incrementAndGet();
        return currentRouteId;
    }

    public NavResponse processLocation(double lat, double lng) {
        if (destination != null && distanceMeters(lat, lng, destination.lat(), destination.lng()) < ARRIVED_DISTANCE_M) {
            return new NavResponse(false, null, null, null, null, null, "목적지에 도착했습니다.", true);
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
            return new NavResponse(false, null, null, null, null, null, null, false);
        }

        ManeuverInfo next = maneuvers.get(nextManeuverIdx);
        double dist = distanceMeters(lat, lng, next.lat(), next.lng());
        int distInt = (int) dist;

        if (dist <= TRIGGER_DISTANCE_M) {
            int totalLanes = getLaneCount(lat, lng);
            List<Integer> targetLanes = targetLanes(next.type(), totalLanes);
            String message = buildMessage(distInt, next.type(), targetLanes);
            return new NavResponse(true, next.type(), distInt, totalLanes, null, targetLanes, message, false);
        }

        return new NavResponse(false, null, null, null, null, null, null, false);
    }

    private String buildMessage(int dist, String type, List<Integer> targetLanes) {
        String maneuverKor = switch (type) {
            case "LEFT" -> "좌회전";
            case "RIGHT" -> "우회전";
            case "UTURN" -> "유턴";
            default -> type;
        };
        String lanes = targetLanes.stream().map(i -> i + "차선").collect(Collectors.joining(", "));
        return dist + "m 앞 " + maneuverKor + ", " + lanes + "으로 이동하세요.";
    }

    private int getLaneCount(double lat, double lng) {
        if (jdbcTemplate == null) return 3;
        try {
            Integer lanes = jdbcTemplate.queryForObject(
                "SELECT lanes FROM a2_link ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326) LIMIT 1",
                Integer.class, lng, lat
            );
            return (lanes != null) ? lanes : 3;
        } catch (Exception e) {
            return 3;
        }
    }

    private List<Integer> targetLanes(String type, int total) {
        return switch (type) {
            case "LEFT", "UTURN" -> List.of(1);
            case "RIGHT" -> List.of(total);
            default -> {
                List<Integer> all = new ArrayList<>();
                for (int i = 1; i <= total; i++) all.add(i);
                yield all;
            }
        };
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
