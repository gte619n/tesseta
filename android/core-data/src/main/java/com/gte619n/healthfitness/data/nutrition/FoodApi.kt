package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.FoodCreateRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// Global (not user-scoped) food catalog (IMPL-13). Authenticated but shared
// across all users — one chicken-breast definition serves everyone.
interface FoodApi {

    @GET("api/foods/search")
    suspend fun search(@Query("q") query: String): List<Food>

    @GET("api/foods/{foodId}")
    suspend fun getFood(@Path("foodId") foodId: String): Food

    // 404 when the barcode is truly unknown (after the backend's OFF fallback).
    @GET("api/foods/barcode/{code}")
    suspend fun barcodeLookup(@Path("code") code: String): Food

    @POST("api/foods")
    suspend fun create(@Body body: FoodCreateRequest): Food

    @POST("api/foods/{foodId}/confirm")
    suspend fun confirm(@Path("foodId") foodId: String): Food
}
