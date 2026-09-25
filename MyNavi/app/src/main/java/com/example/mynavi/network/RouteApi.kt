package com.example.mynavi.network

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface RouteApi {
    @POST("api/route")
    fun getRoute(@Body request: RouteRequest): Call<RouteResponse>
}