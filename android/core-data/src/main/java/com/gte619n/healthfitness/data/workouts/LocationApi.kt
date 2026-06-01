package com.gte619n.healthfitness.data.workouts

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface LocationApi {
    @GET("api/me/gyms")
    suspend fun list(@Query("include") include: String? = null): List<LocationDto>

    @GET("api/me/gyms/{id}")
    suspend fun get(@Path("id") id: String): LocationDto

    @POST("api/me/gyms")
    suspend fun create(@Body req: CreateLocationDto): LocationDto

    @PATCH("api/me/gyms/{id}")
    suspend fun update(@Path("id") id: String, @Body req: UpdateLocationDto): LocationDto

    @DELETE("api/me/gyms/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>

    @POST("api/me/gyms/{id}/default")
    suspend fun setDefault(@Path("id") id: String): Response<Unit>

    @DELETE("api/me/gyms/{id}/photo")
    suspend fun deleteCoverPhoto(@Path("id") id: String): Response<Unit>

    @POST("api/me/gyms/{id}/equipment/{equipmentId}")
    suspend fun addEquipment(
        @Path("id") id: String,
        @Path("equipmentId") eq: String,
    ): Response<Unit>

    @DELETE("api/me/gyms/{id}/equipment/{equipmentId}")
    suspend fun removeEquipment(
        @Path("id") id: String,
        @Path("equipmentId") eq: String,
    ): Response<Unit>

    @PATCH("api/me/gyms/{id}/equipment/{equipmentId}")
    suspend fun updateEquipmentSpecs(
        @Path("id") id: String,
        @Path("equipmentId") eq: String,
        @Body body: SpecsPatchDto,
    ): LocationDto
}
