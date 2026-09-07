package com.gte619n.healthfitness.data.withings

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

// Retrofit service for the Withings connection.
interface WithingsService {
    @GET("api/me/withings/status")
    suspend fun status(): WithingsStatusDto

    // Server-side active probe (forces a token exchange) returning fresh status,
    // to detect a silently-broken connection when the settings screen opens.
    @POST("api/me/withings/check")
    suspend fun check(): WithingsStatusDto

    @POST("api/me/withings/connect")
    suspend fun connect(@Body body: WithingsConnectBody)

    @DELETE("api/me/withings/connect")
    suspend fun disconnect()
}

// Plain data class; Moshi reflection adapter handles (de)serialization.
data class WithingsStatusDto(
    val connected: Boolean,
    val connectedAt: String?,
    val needsReconnect: Boolean = false,
    val brokenReason: String? = null,
)

// The backend redeems the authorization code server-side (it owns the client
// secret). redirectUri must match the one used to start the browser flow —
// Withings enforces it on the token exchange.
data class WithingsConnectBody(
    val code: String,
    val redirectUri: String,
)
