package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.CompositeMealRequest
import com.gte619n.healthfitness.domain.nutrition.DailyRollup
import com.gte619n.healthfitness.domain.nutrition.DescribeMealLogRequest
import com.gte619n.healthfitness.domain.nutrition.DescribeMealRequest
import com.gte619n.healthfitness.domain.nutrition.DescribedMeal
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.domain.nutrition.RelogRequest
import com.gte619n.healthfitness.domain.nutrition.UpdateIngredientRequest
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

    // IMPL-STAB (Workstream E): retry image generation for an entry whose image
    // FAILED or is stuck. Returns the entry (image flips to PENDING).
    @POST("api/me/nutrition/{date}/entries/{entryId}/image/regenerate")
    suspend fun regenerateEntryImage(
        @Path("date") date: String,
        @Path("entryId") entryId: String,
    ): Entry

    // Composite (photo-logged) meal: one entry with ingredients + a generated
    // finished-meal image.
    @POST("api/me/nutrition/{date}/composite-meal")
    suspend fun addCompositeMeal(
        @Path("date") date: String,
        @Body body: CompositeMealRequest,
    ): Entry

    // Describe a meal in free text → resolve to a saved meal (a previous match,
    // or a freshly created+saved one). Nothing is logged yet.
    @POST("api/nutrition/describe")
    suspend fun describeMeal(@Body body: DescribeMealRequest): DescribedMeal

    // Log a described meal onto a day — by resolved mealId, or one-shot
    // description. Returns the composite entry.
    @POST("api/me/nutrition/{date}/describe-meal")
    suspend fun logDescribedMeal(
        @Path("date") date: String,
        @Body body: DescribeMealLogRequest,
    ): Entry

    // Fire-and-forget describe: returns the ANALYZING placeholder immediately
    // (202); resolution runs server-side and the day view polls it in.
    @POST("api/me/nutrition/{date}/describe-meal-async")
    suspend fun describeMealAsync(
        @Path("date") date: String,
        @Body body: DescribeMealLogRequest,
    ): Entry

    // Distinct foods/meals logged recently, deduped, newest first — the
    // add-flow's one-tap "recent meals" list. Each row carries `date`, the id
    // of its source entry and everything needed to re-log it. When `meal` is
    // set (the meal the user is about to log), the backend floats meals usually
    // eaten at that time of day to the top so they survive the limit cut.
    @GET("api/me/nutrition/recent-meals")
    suspend fun recentMeals(
        @Query("days") days: Int,
        @Query("limit") limit: Int,
        @Query("meal") meal: String? = null,
    ): List<Entry>

    // One-tap re-log of a recent entry: server-side copy onto the target day,
    // reusing catalog foods, macros and the finished-meal image (no AI rework).
    @POST("api/me/nutrition/{date}/relog")
    suspend fun relog(
        @Path("date") date: String,
        @Body body: RelogRequest,
    ): Entry

    @PATCH("api/me/nutrition/{date}/entries/{entryId}/ingredients/{index}")
    suspend fun updateIngredient(
        @Path("date") date: String,
        @Path("entryId") entryId: String,
        @Path("index") index: Int,
        @Body body: UpdateIngredientRequest,
    ): Entry

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
