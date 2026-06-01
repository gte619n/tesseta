package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.DailyRollup
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Multi-tenant nutrition REST surface (IMPL-13). Per-day logging + the daily
// macro target. Base path is /api/me/nutrition (the whole backend is
// multi-tenant, like Goals).
interface NutritionApi {

    @GET("api/me/nutrition/{date}")
    suspend fun getDay(@Path("date") date: String): NutritionDay

    @POST("api/me/nutrition/{date}/entries")
    suspend fun addEntry(
        @Path("date") date: String,
        @Body body: EntryRequest,
    ): Entry

    @PATCH("api/me/nutrition/{date}/entries/{entryId}")
    suspend fun patchEntry(
        @Path("date") date: String,
        @Path("entryId") entryId: String,
        @Body body: EntryPatchRequest,
    ): Entry

    @DELETE("api/me/nutrition/{date}/entries/{entryId}")
    suspend fun deleteEntry(
        @Path("date") date: String,
        @Path("entryId") entryId: String,
    ): Unit

    // GET target returns the Macros target, or 204 No Content when unset.
    // Response<> lets us distinguish the 204/empty body from a real payload.
    @GET("api/me/nutrition/target")
    suspend fun getTarget(): Response<Macros>

    @PUT("api/me/nutrition/target")
    suspend fun putTarget(@Body body: Macros): Macros

    @GET("api/me/nutrition")
    suspend fun getRange(
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<DailyRollup>
}
