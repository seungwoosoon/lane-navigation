package com.example.mynavi.network

// 좌표 하나 (스프링의 Point record와 같은 모양)
data class Point(
    val lat: Double,
    val lng: Double
)

// 앱 → 서버 요청 양식 (스프링의 RouteRequest)
data class RouteRequest(
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double
)

// 서버 → 앱 응답 양식 (스프링의 RouteResponse)
data class RouteResponse(
    val path: List<Point>
)

// 앱 → 서버: 1초마다 보내는 현재 GPS
data class LocationUpdate(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

// 서버 → 앱: 위치 전송 응답 (ai_trigger 포함)
data class NavResponse(
    val ai_trigger: Boolean = false,
    val maneuver: String? = null,
    val total_lanes: Int? = null,
    val required_lanes: List<Int>? = null,
    val distance_to_maneuver: Int? = null
)
