package com.example.mynavi.network

data class Point(val lat: Double, val lng: Double)

data class RouteRequest(val start: Point, val end: Point)

data class RouteResponse(val routeId: Int, val path: List<Point>)

data class LocationUpdate(
    val routeId: Int,
    val lat: Double,
    val lng: Double,
    val speed: Double,
    val timestampMs: Long? = null   // GPS 시각 (UTC ms). 재생 모드에서 서버가 Lane AI 재생 시각을 맞추는 데 쓴다
)

data class NavResponse(
    val guide: Boolean = false,
    val maneuver: String? = null,
    val distanceToBranch: Int? = null,
    val totalLanes: Int? = null,
    val currentLane: Int? = null,
    val targetLanes: List<Int>? = null,
    val laneDirections: List<List<String>>? = null,   // 차로별 실제 진행 방향 (0번 = 1차로)
    val message: String? = null,
    val arrived: Boolean = false,
    val currentLaneSource: String? = null   // 현재 차로를 어디서 얻었는지: AI, APP, PLACEHOLDER (모르면 null)
)

/** 서버가 가진 주행 기록 (GET /api/replay). sessionId 가 null 이면 재생할 기록이 없다. */
data class ReplayTrack(
    val sessionId: String? = null,
    val recordingStartMs: Long = 0,
    val points: List<ReplayPoint> = emptyList()
)

data class ReplayPoint(
    val timestampMs: Long,
    val lat: Double,
    val lng: Double,
    val speed: Double? = null
)
