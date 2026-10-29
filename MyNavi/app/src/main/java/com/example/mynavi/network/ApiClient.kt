package com.example.mynavi.network

import com.example.mynavi.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    // MyNavi/local.properties 의 API_BASE_URL (예: API_BASE_URL=http://172.20.10.5:8080/)
    private val BASE_URL = BuildConfig.API_BASE_URL.let { if (it.endsWith("/")) it else "$it/" }

    val routeApi: RouteApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RouteApi::class.java)
}