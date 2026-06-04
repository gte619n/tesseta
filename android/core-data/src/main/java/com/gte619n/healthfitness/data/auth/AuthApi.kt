package com.gte619n.healthfitness.data.auth

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// Backend session-token endpoints (ADR-0010). Served by a DEDICATED OkHttp
// client that carries neither the AuthInterceptor nor the TokenAuthenticator —
// these calls must not attach a session bearer (exchange supplies the Google
// token explicitly) and must never recurse into 401-refresh.
interface AuthApi {

    // Trade a freshly-obtained Google ID token (passed explicitly in the header)
    // for the first access + refresh pair. The only call that needs a Google
    // token — hence the only moment Credential Manager UI can appear.
    @POST("api/auth/exchange")
    suspend fun exchange(@Header("Authorization") bearer: String): TokenResponse

    // Silent, UI-free: trade a refresh token for a new pair. Throws
    // HttpException(401) when the refresh token is dead (revoked/expired).
    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse

    @POST("api/auth/logout")
    suspend fun logout(@Body body: RefreshRequest)

    data class RefreshRequest(val refreshToken: String)

    data class TokenResponse(
        val accessToken: String,
        val accessTokenExpiresAt: Long,
        val refreshToken: String,
        val refreshTokenExpiresAt: Long,
    )
}
