package com.example.naviserver.service;

import com.example.naviserver.dto.Point;
import com.example.naviserver.dto.RouteRequest;
import com.example.naviserver.dto.RouteResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class KakaoRouteService implements RouteService {

    @Value("${kakao.rest.api.key}")
    private String kakaoApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NavigationService navigationService;

    public KakaoRouteService(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @Override
    public RouteResponse findRoute(RouteRequest req) {
        try {
            // 카카오는 경도,위도 순서
            String url = String.format(
                    "https://apis-navi.kakaomobility.com/v1/directions?origin=%f,%f&destination=%f,%f&priority=RECOMMEND",
                    req.startLng(), req.startLat(), req.endLng(), req.endLat()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            System.out.println("[KAKAO RAW] " + resp.getBody());
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode sections = root.path("routes").get(0).path("sections");

            List<Point> routePoints = new ArrayList<>();
            List<ManeuverInfo> maneuvers = new ArrayList<>();

            for (JsonNode section : sections) {
                for (JsonNode road : section.path("roads")) {
                    JsonNode vertexes = road.path("vertexes");
                    for (int i = 0; i + 1 < vertexes.size(); i += 2) {
                        double lng = vertexes.get(i).asDouble();
                        double lat = vertexes.get(i + 1).asDouble();
                        routePoints.add(new Point(lat, lng));
                    }
                }

                for (JsonNode guide : section.path("guides")) {
                    int type = guide.path("type").asInt();
                    if (type == 5) continue; // 목적지 도착은 건너뜀
                    String maneuverType = toManeuverType(type);
                    if (maneuverType.equals("STRAIGHT")) continue; // 직진은 트리거 없음
                    double lat = guide.path("y").asDouble();
                    double lng = guide.path("x").asDouble();
                    maneuvers.add(new ManeuverInfo(lat, lng, maneuverType));
                }
            }

            navigationService.setRoute(routePoints, maneuvers);
            return new RouteResponse(routePoints);

        } catch (Exception e) {
            return fallbackRoute(req);
        }
    }

    private String toManeuverType(int type) {
        return switch (type) {
            case 1 -> "LEFT";
            case 2 -> "RIGHT";
            case 3 -> "UTURN";
            default -> "STRAIGHT";
        };
    }

    private RouteResponse fallbackRoute(RouteRequest req) {
        List<Point> path = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double r = i / 19.0;
            path.add(new Point(
                    req.startLat() + (req.endLat() - req.startLat()) * r,
                    req.startLng() + (req.endLng() - req.startLng()) * r
            ));
        }
        navigationService.setRoute(path, List.of());
        return new RouteResponse(path);
    }
}
