package com.example.mynavi.network

data class Point(val lat: Double, val lng: Double)

data class RouteRequest(val start: Point, val end: Point)

data class RouteResponse(val routeId: Int, val path: List<Point>)

data class LocationUpdate(
    val routeId: Int,
    val lat: Double,
    val lng: Double,
    val speed: Double
)

data class NavResponse(
    val guide: Boolean = false,
    val maneuver: String? = null,
    val distanceToBranch: Int? = null,
    val totalLanes: Int? = null,
    val currentLane: Int? = null,
    val targetLanes: List<Int>? = null,
    val message: String? = null,
    val arrived: Boolean = false
)
