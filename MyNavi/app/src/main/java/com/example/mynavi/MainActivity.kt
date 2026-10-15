package com.example.mynavi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import com.example.mynavi.network.ApiClient
import com.example.mynavi.network.LocationUpdate
import com.example.mynavi.network.NavResponse
import com.example.mynavi.network.Point
import com.example.mynavi.network.RouteRequest
import com.example.mynavi.network.RouteResponse
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapAuthException
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var info: TextView
    private lateinit var etDestination: EditText
    private lateinit var btnRoute: Button
    private lateinit var btnStop: Button
    private lateinit var lanePanel: LinearLayout
    private lateinit var tvManeuver: TextView
    private lateinit var laneRow: LinearLayout
    private lateinit var tvGuidance: TextView

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var kakaoMap: KakaoMap
    private lateinit var tts: TextToSpeech
    private var myLabel: Label? = null
    private var currentLoc: Location? = null
    private var isNavigating = false
    private var lastSpokenDistance = Int.MAX_VALUE
    private var currentRouteId = 0
    private var lastUserInteractionTime = 0L
    private val AUTO_CENTER_DELAY_MS = 5000L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            info.text = "위치 권한 허용됨"
            startLocationUpdates()
        } else {
            info.text = "위치 권한 거부됨"
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            currentLoc = loc
            info.text = "속도 ${String.format("%.1f", loc.speed * 3.6)} km/h  " +
                    "정확도 ${String.format("%.0f", loc.accuracy)}m"

            val latLng = LatLng.from(loc.latitude, loc.longitude)
            val now = System.currentTimeMillis()
            if (now - lastUserInteractionTime > AUTO_CENTER_DELAY_MS) {
                kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(latLng))
            }

            if (myLabel == null) {
                val styles = kakaoMap.labelManager!!
                    .addLabelStyles(LabelStyles.from(LabelStyle.from(makeDot())))
                myLabel = kakaoMap.labelManager!!.layer!!
                    .addLabel(LabelOptions.from(latLng).setStyles(styles))
            } else {
                myLabel!!.moveTo(latLng)
            }

            if (isNavigating) {
                sendLocationToServer(loc)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map_view)
        info = findViewById(R.id.info)
        etDestination = findViewById(R.id.et_destination)
        btnRoute = findViewById(R.id.btn_route)
        btnStop = findViewById(R.id.btn_stop)
        fused = LocationServices.getFusedLocationProviderClient(this)

        lanePanel = findViewById(R.id.lane_panel)
        tvManeuver = findViewById(R.id.tv_maneuver)
        laneRow = findViewById(R.id.lane_row)
        tvGuidance = findViewById(R.id.tv_guidance)

        btnRoute.setOnClickListener { requestRoute() }
        btnStop.setOnClickListener { stopNavigation() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
            }
        }

        Log.e("KEYHASH", getKeyHash())

        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {}
                override fun onMapError(error: Exception) {
                    info.text = "map error: ${error.message}"
                    if (error is MapAuthException) {
                        info.text = "${error.errorCode}"
                    }
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    info.text = "map ready"
                    kakaoMap = map
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastUserInteractionTime = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        mapView.resume()
    }

    override fun onPause() {
        super.onPause()
        mapView.pause()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun requestRoute() {
        val loc = currentLoc
        if (loc == null) {
            info.text = "현재 위치 없음"
            return
        }

        val input = etDestination.text.toString().trim()
        val parts = input.split(",")
        if (parts.size != 2) {
            info.text = "목적지 형식 오류 (예: 37.4502,126.6533)"
            return
        }
        val destLat = parts[0].trim().toDoubleOrNull()
        val destLng = parts[1].trim().toDoubleOrNull()
        if (destLat == null || destLng == null) {
            info.text = "목적지 좌표 파싱 오류"
            return
        }

        info.text = "경로 요청 중..."
        val request = RouteRequest(
            start = Point(loc.latitude, loc.longitude),
            end = Point(destLat, destLng)
        )

        ApiClient.routeApi.getRoute(request).enqueue(object : Callback<RouteResponse> {
            override fun onResponse(call: Call<RouteResponse>, response: Response<RouteResponse>) {
                val body = response.body()
                if (body != null) {
                    currentRouteId = body.routeId
                    drawRoute(body.path)
                    isNavigating = true
                    btnRoute.isEnabled = false
                    btnStop.isEnabled = true
                    info.text = "내비게이션 시작 (경유점 ${body.path.size}개)"
                } else {
                    info.text = "서버 응답 없음 (${response.code()})"
                }
            }
            override fun onFailure(call: Call<RouteResponse>, t: Throwable) {
                info.text = "요청 실패: ${t.message}"
            }
        })
    }

    private fun stopNavigation() {
        isNavigating = false
        lastSpokenDistance = Int.MAX_VALUE
        currentRouteId = 0
        btnRoute.isEnabled = true
        btnStop.isEnabled = false
        info.text = "내비게이션 중지"
        kakaoMap.routeLineManager?.layer?.removeAll()
    }

    private fun sendLocationToServer(loc: Location) {
        val update = LocationUpdate(currentRouteId, loc.latitude, loc.longitude, loc.speed.toDouble())
        ApiClient.routeApi.sendLocation(update).enqueue(object : Callback<NavResponse> {
            override fun onResponse(call: Call<NavResponse>, response: Response<NavResponse>) {
                val nav = response.body() ?: return
                Log.d("NAV", "위치 전송 응답: $nav")
                handleNavResponse(nav)
            }
            override fun onFailure(call: Call<NavResponse>, t: Throwable) {
                Log.w("NAV", "위치 전송 실패: ${t.message}")
            }
        })
    }

    private fun handleNavResponse(nav: NavResponse) {
        if (nav.arrived) {
            isNavigating = false
            btnRoute.isEnabled = true
            btnStop.isEnabled = false
            kakaoMap.routeLineManager?.layer?.removeAll()
            tts.speak("목적지에 도착했습니다.", TextToSpeech.QUEUE_FLUSH, null, null)
            lastSpokenDistance = Int.MAX_VALUE

            tvManeuver.text = "목적지 도착"
            tvGuidance.text = "목적지에 도착했습니다."
            laneRow.removeAllViews()
            laneRow.visibility = View.GONE
            lanePanel.visibility = View.VISIBLE
            lanePanel.postDelayed({
                lanePanel.visibility = View.GONE
                laneRow.visibility = View.VISIBLE
            }, 3000)
            return
        }

        val dist = nav.distanceToBranch
        if (nav.guide && dist != null && nav.message != null) {
            showLanePanel(nav)

            val milestone = when {
                dist <= 50 && lastSpokenDistance > 50 -> true
                dist <= 100 && lastSpokenDistance > 100 -> true
                dist <= 300 && lastSpokenDistance > 300 -> true
                else -> false
            }
            if (milestone) {
                tts.speak(nav.message, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            lastSpokenDistance = dist
        } else if (!nav.guide) {
            lanePanel.visibility = View.GONE
            lastSpokenDistance = Int.MAX_VALUE
        }
    }

    private fun showLanePanel(nav: NavResponse) {
        val maneuverKor = when (nav.maneuver) {
            "LEFT" -> "좌회전"
            "RIGHT" -> "우회전"
            "UTURN" -> "유턴"
            else -> nav.maneuver ?: ""
        }
        tvManeuver.text = "${nav.distanceToBranch}m 앞 $maneuverKor"
        tvGuidance.text = nav.message

        val total = nav.totalLanes ?: 3
        val targets = nav.targetLanes ?: emptyList()
        val current = nav.currentLane

        laneRow.removeAllViews()
        val dp = resources.displayMetrics.density
        val cellSize = (56 * dp).toInt()
        val margin = (4 * dp).toInt()

        for (i in 1..total) {
            val cell = TextView(this)
            val params = LinearLayout.LayoutParams(cellSize, cellSize).apply {
                setMargins(margin, 0, margin, 0)
            }
            cell.layoutParams = params
            cell.gravity = Gravity.CENTER
            cell.textSize = 22f

            val bg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }

            when {
                current == i -> {
                    bg.setColor(Color.WHITE)
                    bg.setStroke((2 * dp).toInt(), Color.WHITE)
                    cell.text = "🚗"
                }
                targets.contains(i) -> {
                    bg.setColor(Color.rgb(255, 200, 0))
                    bg.setStroke((2 * dp).toInt(), Color.WHITE)
                    cell.text = "●"
                    cell.setTextColor(Color.rgb(26, 58, 107))
                }
                else -> {
                    bg.setColor(Color.TRANSPARENT)
                    bg.setStroke((2 * dp).toInt(), Color.WHITE)
                    cell.text = ""
                    cell.setTextColor(Color.WHITE)
                }
            }
            cell.background = bg
            laneRow.addView(cell)
        }

        lanePanel.visibility = View.VISIBLE
    }

    private fun drawRoute(path: List<Point>) {
        val latLngs = path.map { LatLng.from(it.lat, it.lng) }
        val stylesSet = RouteLineStylesSet.from(
            RouteLineStyles.from(RouteLineStyle.from(16f, Color.rgb(30, 110, 255)))
        )
        val segment = RouteLineSegment.from(latLngs, stylesSet.getStyles(0))
        val options = RouteLineOptions.from(segment).setStylesSet(stylesSet)
        kakaoMap.routeLineManager!!.layer.addRouteLine(options)
    }

    private fun getKeyHash(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = packageInfo.signingInfo!!.apkContentsSigners[0].toByteArray()
        val md = MessageDigest.getInstance("SHA")
        md.update(sig)
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    private fun makeDot(): Bitmap {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(24f, 24f, 24f, paint)
        paint.color = Color.rgb(30, 110, 255)
        canvas.drawCircle(24f, 24f, 17f, paint)
        return bmp
    }
}
