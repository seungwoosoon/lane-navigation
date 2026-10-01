package com.example.mynavi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.mynavi.network.ApiClient
import com.example.mynavi.network.LocationUpdate
import com.example.mynavi.network.NavResponse
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

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var kakaoMap: KakaoMap
    private var myLabel: Label? = null
    private var currentLoc: Location? = null
    private var isNavigating = false

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
            kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(latLng))

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

        btnRoute.setOnClickListener { requestRoute() }
        btnStop.setOnClickListener { stopNavigation() }

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

    override fun onResume() {
        super.onResume()
        mapView.resume()
    }

    override fun onPause() {
        super.onPause()
        mapView.pause()
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
        val request = RouteRequest(loc.latitude, loc.longitude, destLat, destLng)

        ApiClient.routeApi.getRoute(request).enqueue(object : Callback<RouteResponse> {
            override fun onResponse(call: Call<RouteResponse>, response: Response<RouteResponse>) {
                val body = response.body()
                if (body != null) {
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
        btnRoute.isEnabled = true
        btnStop.isEnabled = false
        info.text = "내비게이션 중지"
    }

    private fun sendLocationToServer(loc: Location) {
        val update = LocationUpdate(loc.latitude, loc.longitude, System.currentTimeMillis())
        ApiClient.routeApi.sendLocation(update).enqueue(object : Callback<NavResponse> {
            override fun onResponse(call: Call<NavResponse>, response: Response<NavResponse>) {
                val nav = response.body() ?: return
                Log.d("NAV", "위치 전송 응답: $nav")
                // TODO Phase 2: nav.ai_trigger == true 일 때 카메라/AI 실행
            }
            override fun onFailure(call: Call<NavResponse>, t: Throwable) {
                Log.w("NAV", "위치 전송 실패: ${t.message}")
            }
        })
    }

    private fun drawRoute(path: List<com.example.mynavi.network.Point>) {
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
