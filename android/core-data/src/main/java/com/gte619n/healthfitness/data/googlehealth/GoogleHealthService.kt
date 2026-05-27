package com.gte619n.healthfitness.data.googlehealth

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit service for the three `/api/me/google-health` endpoints:
 *  - GET /status returns the connected flag and ISO connectedAt.
 *  - POST /connect accepts `{ serverAuthCode }` (Android branch added
 *    on the backend in this IMPL alongside the existing web shape).
 *  - DELETE /connect clears the per-user connection record.
 */
internal interface GoogleHealthService {

    @GET("api/me/google-health/status")
    suspend fun status(): GoogleHealthStatusDto

    @POST("api/me/google-health/connect")
    suspend fun connect(@Body body: ConnectBody)

    @DELETE("api/me/google-health/connect")
    suspend fun disconnect()
}

// Reflective Moshi adapter — `connectedAt` is an ISO-8601 string per
// `GoogleHealthConnectController.StatusResponse`, decoded into [Instant]
// downstream in the repository so the domain layer carries epoch seconds
// rather than a JSR-310 type.
internal data class GoogleHealthStatusDto(
    val connected: Boolean,
    val connectedAt: String?,
)

// Android-branch shape of the connect body. The backend distinguishes
// Android (`serverAuthCode`) from web (`refreshToken` + `accessToken`).
// Documented in IMPL-AND-02 section "Backend touchpoint".
internal data class ConnectBody(
    val serverAuthCode: String,
)
