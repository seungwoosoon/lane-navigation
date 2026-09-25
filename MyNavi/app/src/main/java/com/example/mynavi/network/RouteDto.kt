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