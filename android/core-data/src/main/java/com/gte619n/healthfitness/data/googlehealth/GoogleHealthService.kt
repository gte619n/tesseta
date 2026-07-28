package com.gte619n.healthfitness.data.googlehealth

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

// Retrofit service for the Google Health connection.
interface GoogleHealthService {
    @GET("api/me/google-health/status")
    suspend fun status(): GoogleHealthStatusDto

    // Actively probe the connection server-side (forces a token exchange) and
    // return the freshly-updated status. Used to detect a silently-broken
    // connection when the settings screen opens.
    @POST("api/me/google-health/check")
    suspend fun check(): GoogleHealthStatusDto

    @POST("api/me/google-health/connect")
    suspend fun connect(@Body body: ConnectBody)

    @DELETE("api/me/google-health/connect")
    suspend fun disconnect()
}

// Plain data class; Moshi reflection adapter handles (de)serialization.
// needsReconnect is true when the stored refresh token has died (revoked or
// expired) and the user must reconnect; brokenReason is a short diagnostic.
data class GoogleHealthStatusDto(
    val connected: Boolean,
    val connectedAt: String?,
    val needsReconnect: Boolean = false,
    val brokenReason: String? = null,
)

// Android branch of the connect body. The backend distinguishes the Android
// shape (serverAuthCode) from the web shape (refreshToken + accessToken).
data class ConnectBody(
    val serverAuthCode: String,
)
