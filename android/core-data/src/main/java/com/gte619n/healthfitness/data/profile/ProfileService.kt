package com.gte619n.healthfitness.data.profile

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

/**
 * Retrofit service for the backend's `/api/me` profile endpoints. The
 * backend's [WhoAmIController] returns `{userId, email, displayName,
 * heightCm}` on GET and accepts `{heightCm}` on PATCH; this contract
 * mirrors that exactly. Internal so feature modules can't hit Retrofit
 * directly — everything funnels through [ProfileRepositoryImpl].
 */
internal interface ProfileService {

    @GET("api/me")
    suspend fun get(): ProfileDto

    @PATCH("api/me")
    suspend fun patch(@Body body: PatchProfileBody): ProfileDto
}

// Reflective Moshi adapter (KotlinJsonAdapterFactory) handles the
// (de)serialisation — IMPL-AND-00 didn't wire moshi-kotlin-codegen.
internal data class ProfileDto(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val heightCm: Int?,
)

internal data class PatchProfileBody(
    val heightCm: Int?,
)
