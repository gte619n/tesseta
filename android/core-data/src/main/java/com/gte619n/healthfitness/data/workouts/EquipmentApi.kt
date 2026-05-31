package com.gte619n.healthfitness.data.workouts

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface EquipmentApi {
    @GET("api/equipment")
    suspend fun search(
        @Query("search") search: String? = null,
        @Query("category") category: String? = null,
        @Query("sub") sub: String? = null,
    ): List<EquipmentDto>

    @GET("api/equipment/{id}")
    suspend fun get(@Path("id") id: String): EquipmentDto

    @GET("api/equipment/categories")
    suspend fun categories(): Map<String, List<String>>

    @POST("api/me/equipment")
    suspend fun submit(@Body req: CreateEquipmentDto): EquipmentDto

    @GET("api/me/equipment")
    suspend fun mySubmissions(): List<EquipmentDto>

    @DELETE("api/me/equipment/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>
}
