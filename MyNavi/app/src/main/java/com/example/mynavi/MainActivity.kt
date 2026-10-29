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
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import com.example.mynavi.ui.LaneGuideView
import com.example.mynavi.ui.TurnIconView
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
import com.kakao.vectormap.MapGravity
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
    private lateinit var topOverlay: LinearLayout
    private lateinit var bottomSheet: LinearLayout
    private lateinit var inputGroup: LinearLayout
    private lateinit var navGroup: LinearLayout
    private lateinit var tvNavDest: TextView
    private lateinit var lanePanel: LinearLayout
    private lateinit var turnHeader: LinearLayout
    private lateinit var turnIcon: TurnIconView
    private lateinit var tvDistance: TextView
    private lateinit var tvManeuver: TextView
    private lateinit var laneBody: View
    private lateinit var laneView: LaneGuideView

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
    private val hideArrivalPanel = Runnable { lanePanel.visibility = View.GONE }

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
            info.text = "${String.format("%.1f", loc.speed * 3.6)} km/h  ·  " +
                    "GPS ±${String.format("%.0f", loc.accuracy)}m"

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
        // 지도 위로 화면을 꽉 채우고, 상태바·내비게이션바 아이콘은 어둡게 (밝은 지도 위)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map_view)
        info = findViewById(R.id.info)
        etDestination = findViewById(R.id.et_destination)
        btnRoute = findViewById(R.id.btn_route)
        btnStop = findViewById(R.id.btn_stop)
        fused = LocationServices.getFusedLocationProviderClient(this)

        topOverlay = findViewById(R.id.top_overlay)
        bottomSheet = findViewById(R.id.bottom_sheet)
        inputGroup = findViewById(R.id.input_group)
        navGroup = findViewById(R.id.nav_group)
        tvNavDest = findViewById(R.id.tv_nav_dest)

        lanePanel = findViewById(R.id.lane_panel)
        turnHeader = findViewById(R.id.turn_header)
        turnIcon = findViewById(R.id.turn_icon)
        tvDistance = findViewById(R.id.tv_distance)
        tvManeuver = findViewById(R.id.tv_maneuver)
        laneBody = findViewById(R.id.lane_body)
        laneView = findViewById(R.id.lane_view)
        lanePanel.clipToOutline = true   // 파란 머리 부분도 카드의 둥근 모서리를 따르도록

        applySystemBarInsets()
        bottomSheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeMapLogo() }

        btnRoute.setOnClickListener { requestRoute() }
        btnStop.setOnClickListener { stopNavigation() }
        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                requestRoute()
                true
            } else {
                false
            }
        }

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
                    info.text = "지도 준비 완료"
                    kakaoMap = map
                    placeMapLogo()
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
        btnRoute.isEnabled = false
        hideKeyboard()
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
                    tvNavDest.text = "목적지 ${String.format("%.4f", destLat)}, ${String.format("%.4f", destLng)}"
                    setNavigatingUi(true)
                    info.text = "안내 시작 (경유점 ${body.path.size}개)"
                } else {
                    btnRoute.isEnabled = true
                    info.text = "서버 응답 없음 (${response.code()})"
                }
            }
            override fun onFailure(call: Call<RouteResponse>, t: Throwable) {
                btnRoute.isEnabled = true
                info.text = "요청 실패: ${t.message}"
            }
        })
    }

    private fun stopNavigation() {
        isNavigating = false
        lastSpokenDistance = Int.MAX_VALUE
        currentRouteId = 0
        setNavigatingUi(false)
        lanePanel.removeCallbacks(hideArrivalPanel)
        lanePanel.visibility = View.GONE
        info.text = "안내 종료"
        kakaoMap.routeLineManager?.layer?.removeAll()
    }

    /** 하단 시트를 '목적지 입력' 또는 '안내 중' 화면으로 바꾼다. */
    private fun setNavigatingUi(navigating: Boolean) {
        inputGroup.visibility = if (navigating) View.GONE else View.VISIBLE
        navGroup.visibility = if (navigating) View.VISIBLE else View.GONE
        btnRoute.isEnabled = !navigating
        btnStop.isEnabled = navigating
        etDestination.clearFocus()   // 입력 화면으로 돌아와도 입력칸이 선택된 채로 남지 않게
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(etDestination.windowToken, 0)
        etDestination.clearFocus()
    }

    /**
     * 상태바 높이만큼 상단 카드를, 내비게이션바(키보드가 열리면 키보드) 높이만큼
     * 하단 시트를 안쪽으로 민다.
     */
    private fun applySystemBarInsets() {
        val topBase = topOverlay.paddingTop
        val bottomBase = bottomSheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            topOverlay.setPadding(
                topOverlay.paddingLeft, topBase + bars.top,
                topOverlay.paddingRight, topOverlay.paddingBottom
            )
            bottomSheet.setPadding(
                bottomSheet.paddingLeft, bottomSheet.paddingTop,
                bottomSheet.paddingRight, bottomBase + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    /** 카카오 로고가 하단 시트에 가리지 않도록 시트 바로 위로 옮긴다. */
    private fun placeMapLogo() {
        if (!::kakaoMap.isInitialized) return
        val dp = resources.displayMetrics.density
        kakaoMap.logo?.setPosition(
            MapGravity.BOTTOM or MapGravity.RIGHT,
            12 * dp,
            bottomSheet.height + 8 * dp
        )
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
            setNavigatingUi(false)
            kakaoMap.routeLineManager?.layer?.removeAll()
            tts.speak("목적지에 도착했습니다.", TextToSpeech.QUEUE_FLUSH, null, null)
            lastSpokenDistance = Int.MAX_VALUE
            showArrivalPanel()
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
        val total = nav.totalLanes
        val targets = nav.targetLanes ?: emptyList()
        val current = nav.currentLane

        lanePanel.removeCallbacks(hideArrivalPanel)
        turnHeader.setBackgroundResource(R.drawable.bg_turn_header)
        turnIcon.type = nav.maneuver
        tvDistance.text = formatDistance(nav.distanceToBranch ?: 0)

        // 두 번째 줄: "좌회전 · 왼쪽으로 1차로 이동" (현재 차로를 알 때만 이동 안내를 붙임)
        val move = laneMoveText(current, targets)
        tvManeuver.text = if (move != null) "$maneuverKor · $move" else maneuverKor

        // 정밀지도 범위 밖이라 서버가 차로 수를 모르면 차로 그림은 숨기고 회전 안내만 보여준다
        if (total != null && total > 0) {
            laneView.setLanes(total, targets, current, nav.maneuver)
            laneBody.visibility = View.VISIBLE
        } else {
            laneBody.visibility = View.GONE
        }
        lanePanel.visibility = View.VISIBLE
    }

    private fun showArrivalPanel() {
        turnHeader.setBackgroundResource(R.drawable.bg_arrive_header)
        turnIcon.type = "ARRIVE"
        tvDistance.text = "도착"
        tvManeuver.text = "목적지에 도착했습니다"
        laneBody.visibility = View.GONE
        lanePanel.visibility = View.VISIBLE
        lanePanel.removeCallbacks(hideArrivalPanel)
        lanePanel.postDelayed(hideArrivalPanel, 3000)
    }

    /** 280 → "280m", 1250 → "1.3km" (단위 글자는 작게). */
    private fun formatDistance(meters: Int): CharSequence {
        val (num, unit) = if (meters >= 1000) {
            String.format("%.1f", meters / 1000.0) to "km"
        } else {
            meters.toString() to "m"
        }
        return SpannableString(num + unit).apply {
            setSpan(RelativeSizeSpan(0.6f), num.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 현재 차로에서 가장 가까운 목표 차로까지의 이동 안내. 현재 차로를 모르면 null. */
    private fun laneMoveText(current: Int?, targets: List<Int>): String? {
        if (current == null || targets.isEmpty()) return null
        if (current in targets) return "현재 차로 유지"
        val nearest = targets.minByOrNull { kotlin.math.abs(it - current) } ?: return null
        val diff = nearest - current
        return if (diff < 0) "왼쪽으로 ${-diff}차로 이동" else "오른쪽으로 ${diff}차로 이동"
    }

    private fun drawRoute(path: List<Point>) {
        val latLngs = path.map { LatLng.from(it.lat, it.lng) }
        val stylesSet = RouteLineStylesSet.from(
            RouteLineStyles.from(RouteLineStyle.from(16f, getColor(R.color.navi_primary)))
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
        paint.color = getColor(R.color.navi_primary)
        canvas.drawCircle(24f, 24f, 17f, paint)
        return bmp
    }
}
