package com.gte619n.healthfitness.data.workouts

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// IMPL-GYM-003: gym-video equipment scan endpoints (see GymVideoScanController).
interface GymScanApi {
    @POST("api/me/gyms/{locationId}/equipment/scan")
    suspend fun register(
        @Path("locationId") locationId: String,
        @Body req: ScanRegisterRequestDto,
    ): ScanRegisterResponseDto

    @POST("api/me/gyms/{locationId}/equipment/scan/{scanId}/start")
    suspend fun start(
        @Path("locationId") locationId: String,
        @Path("scanId") scanId: String,
    ): ScanStatusResponseDto

    @GET("api/me/gyms/{locationId}/equipment/scan/{scanId}")
    suspend fun status(
        @Path("locationId") locationId: String,
        @Path("scanId") scanId: String,
    ): ScanStatusResponseDto

    @POST("api/me/gyms/{locationId}/equipment/scan/{scanId}/confirm")
    suspend fun confirm(
        @Path("locationId") locationId: String,
        @Path("scanId") scanId: String,
        @Body req: ImportConfirmRequestDto,
    ): ImportConfirmResponseDto
}
