package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.FoodCreateRequest
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

// Thin wrapper over FoodApi (the global food catalog).
@Singleton
class FoodRepository @Inject constructor(
    private val api: FoodApi,
) {
    suspend fun search(query: String): List<Food> = api.search(query)

    suspend fun food(foodId: String): Food = api.getFood(foodId)

    /**
     * Resolve a scanned barcode. Returns null on a 404 (truly unknown product,
     * even after the backend's Open Food Facts fallback) so the caller can offer
     * the label-photo path; other errors propagate.
     */
    suspend fun barcodeLookup(code: String): Food? =
        try {
            api.barcodeLookup(code)
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }

    suspend fun create(body: FoodCreateRequest): Food = api.create(body)

    suspend fun confirm(foodId: String): Food = api.confirm(foodId)
}
