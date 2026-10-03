package com.gte619n.healthfitness.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter

/**
 * IMPL-IOS-01 (logging spine) — online-first [FoodRepository] over the existing
 * `api/foods/…` catalog endpoints (paths 1:1 with Android's `FoodApi`). Backs the
 * add-food sheet's catalog search + barcode lookup + delete.
 *
 * Online-first: there is no local catalog cache yet, so [localSearch] is empty and
 * [warmFromIds] is a no-op (the network [search] is the only source). The warm /
 * local-first cache lands with the sync/outbox layer.
 */
class HttpFoodRepository(private val client: HttpClient) : FoodRepository {

    override suspend fun search(query: String): List<Food> =
        if (query.isBlank()) emptyList()
        else client.get("api/foods/search") { parameter("q", query) }.body()

    /** No local catalog cache online-first — [search] serves all hits. */
    override suspend fun localSearch(query: String): List<Food> = emptyList()

    override suspend fun barcodeLookup(code: String): Food? =
        // 404 (unknown barcode) surfaces as a thrown error under expectSuccess; the
        // caller wants null, not a crash.
        runCatching { client.get("api/foods/barcode/$code").body<Food>() }.getOrNull()

    /** No-op online-first (nothing to warm without a local cache). */
    override suspend fun warmFromIds(foodIds: Collection<String>) {}

    override suspend fun deleteFood(foodId: String) {
        client.delete("api/foods/$foodId")
    }
}
