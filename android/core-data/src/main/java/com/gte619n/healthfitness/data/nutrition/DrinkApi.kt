package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.Food
import retrofit2.http.GET

/**
 * IMPL-DRINK-01 — read side of the user's personal drink catalog.
 *
 * `GET /api/me/drinks` returns MY non-archived drinks (`createdBy == me`,
 * `category="drink"`), newest first. Each element is the same `FoodResponse`
 * shape as the food catalog plus a non-null `alcohol` block, so it deserializes
 * straight into [Food] (which now carries a nullable `AlcoholInfo`).
 *
 * Drink CREATION / edit / image-regen live on the WEB (D4); Android only reads
 * this list to warm its offline cache and log entries. Logging a drink reuses the
 * ordinary add-entry path (`meal=DRINKS`), NOT an endpoint here.
 */
interface DrinkApi {

    @GET("api/me/drinks")
    suspend fun myDrinks(): List<Food>
}
