package com.gte619n.healthfitness.data.googlehealth

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

// Retrofit service for the Google Health connection.
interface GoogleHealthService {
    @GET("api/me/google-health/status")
    suspend fun status(): GoogleHealthStatusDto

    @POST("api/me/google-health/connect")
    suspend fun connect(@Body body: ConnectBody)

    @DELETE("api/me/google-health/connect")
    suspend fun disconnect()
}

// Plain data class; Moshi reflection adapter handles (de)serialization.
data class GoogleHealthStatusDto(
    val connected: Boolean,
    val connectedAt: String?,
)

// Android branch of the connect body. The backend distinguishes the Android
// shape (serverAuthCode) from the web shape (refreshToken + accessToken).
data class ConnectBody(
    val serverAuthCode: String,
)
