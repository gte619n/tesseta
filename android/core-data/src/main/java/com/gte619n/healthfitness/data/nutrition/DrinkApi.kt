package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.DrinkProposal
import com.gte619n.healthfitness.domain.nutrition.DrinkUpsertRequest
import com.gte619n.healthfitness.domain.nutrition.Food
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * IMPL-DRINK-01 — the user's personal drink catalog.
 *
 * `GET /api/me/drinks` returns MY non-archived drinks (`createdBy == me`,
 * `category="drink"`), newest first. Each element is the same `FoodResponse`
 * shape as the food catalog plus a non-null `alcohol` block, so it deserializes
 * straight into [Food] (which carries a nullable `AlcoholInfo` + `servingMacros`).
 *
 * The Drink card only READS this list (to warm its offline cache + log entries).
 * The remaining verbs power the phone-side drink MANAGEMENT surface in Settings
 * (add / edit / regenerate image / archive), mirroring the web `me/drinks` page.
 */
interface DrinkApi {

    @GET("api/me/drinks")
    suspend fun myDrinks(): List<Food>

    /**
     * `POST /api/me/drinks/analyze` — turn a free-text name into a drink proposal
     * (ABV%, volume, macros, alcohol facts). Returns HTTP 422 when AI is
     * unavailable; the caller inspects [Response.code] and falls back to manual
     * entry, so this returns the raw [Response] rather than the body directly.
     */
    @POST("api/me/drinks/analyze")
    suspend fun analyze(@Body body: AnalyzeDrinkRequest): Response<DrinkProposal>

    /** `POST /api/me/drinks` — create a drink (201). Kicks off image generation. */
    @POST("api/me/drinks")
    suspend fun create(@Body body: DrinkUpsertRequest): Food

    /** `PUT /api/me/drinks/{id}` — edit a drink; re-derives the alcohol math. */
    @PUT("api/me/drinks/{id}")
    suspend fun update(@Path("id") id: String, @Body body: DrinkUpsertRequest): Food

    /** `POST /api/me/drinks/{id}/image/regenerate` — re-run image generation (202). */
    @POST("api/me/drinks/{id}/image/regenerate")
    suspend fun regenerateImage(@Path("id") id: String): Food

    /** `DELETE /api/me/drinks/{id}` — archive / soft-delete a drink (204). */
    @DELETE("api/me/drinks/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>

    /**
     * `PUT /api/me/drinks/order` — persist the user's drink display order (204).
     * The next `GET /api/me/drinks` returns the drinks in this order.
     */
    @PUT("api/me/drinks/order")
    suspend fun reorder(@Body body: ReorderDrinksRequest): Response<Unit>
}

/** Body for `POST /api/me/drinks/analyze`. */
data class AnalyzeDrinkRequest(val name: String)

/** Body for `PUT /api/me/drinks/order` — my drink ids in the desired order. */
data class ReorderDrinksRequest(val orderedIds: List<String>)
