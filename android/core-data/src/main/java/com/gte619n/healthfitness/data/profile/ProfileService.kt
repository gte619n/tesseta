package com.gte619n.healthfitness.data.profile

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

// Retrofit service for the current user's profile.
// Matches the backend's actual contract: GET /api/me and a partial
// PATCH /api/me carrying only the fields being changed.
interface ProfileService {
    @GET("api/me")
    suspend fun get(): ProfileDto

    @PATCH("api/me")
    suspend fun patch(@Body body: PatchProfileBody): ProfileDto
}

// Plain data class; Moshi reflection adapter handles (de)serialization.
data class ProfileDto(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val heightCm: Int?,
)

// Partial update body. A null heightCm clears the stored height.
data class PatchProfileBody(
    val heightCm: Int?,
)
