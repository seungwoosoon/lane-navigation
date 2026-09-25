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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.mynavi.network.ApiClient
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

    // 화면 부품
    private lateinit var mapView: MapView
    private lateinit var info: TextView

    // 위치 창구
    private lateinit var fused: FusedLocationProviderClient

    // 조종기
    private lateinit var kakaoMap: KakaoMap
    private var myLabel: Label? = null

    // 최근 내 위치 (버튼 누를 때 출발지로 씀)
    private var currentLoc: Location? = null

    // 위치 권한 요청 (마지막 재료가 람다면 괄호 밖으로 빼도 됨)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            info.text = "location permission"
            startLocationUpdates()
        } else {
            info.text = "location deny"
        }
    }

    // 위치 들어오면 할 일
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation
            if (loc == null) {
                info.text = "no location..."
            } else {
                currentLoc = loc
                info.text = "speed = ${loc.speed * 3.6}, accuracy = ${loc.accuracy}"

                val latlon = LatLng.from(loc.latitude, loc.longitude)
                kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(latlon))

                if (myLabel == null) {
                    val styles = kakaoMap.labelManager!!.addLabelStyles(LabelStyles.from(LabelStyle.from(makeDot())))
                    myLabel = kakaoMap.labelManager!!.layer!!.addLabel(LabelOptions.from(latlon).setStyles(styles))
                } else {
                    myLabel!!.moveTo(latlon)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map_view)
        info = findViewById(R.id.info)
        fused = LocationServices.getFusedLocationProviderClient(this)

        findViewById<Button>(R.id.btn_route).setOnClickListener {
            requestRoute()
        }

        Log.e("KEYHASH", getKeyHash())

        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                }

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

    // 서버에 경로 요청
    private fun requestRoute() {
        val loc = currentLoc
        if (loc == null) {
            info.text = "아직 현재 위치가 없어요"
            return
        }

        // 도착지: 일단 고정 (인하대 근처)
        val request = RouteRequest(loc.latitude, loc.longitude, 37.4502, 126.6533)

        ApiClient.routeApi.getRoute(request).enqueue(object : Callback<RouteResponse> {
            override fun onResponse(call: Call<RouteResponse>, response: Response<RouteResponse>) {
                val body = response.body()
                if (body != null) {
                    drawRoute(body.path)
                    info.text = "경로 받음: 점 ${body.path.size}개"
                }
            }

            override fun onFailure(call: Call<RouteResponse>, t: Throwable) {
                info.text = "요청 실패: ${t.message}"
            }
        })
    }

    // 받은 경로를 지도에 선으로 그리기
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