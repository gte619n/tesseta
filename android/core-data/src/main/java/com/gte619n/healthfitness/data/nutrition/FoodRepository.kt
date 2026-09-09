package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.db.dao.CatalogCacheDao
import com.gte619n.healthfitness.data.db.entity.CatalogCacheEntity
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.FoodCreateRequest
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over FoodApi (the global food catalog).
 *
 * offline-fix (ADR-0018) — the catalog is network-only, so this repo now keeps a
 * bounded [CatalogCacheEntity] read cache (type = [CACHE_TYPE]) of the foods the
 * user has actually fetched: every [search]/[food]/[barcodeLookup]/[create]/
 * [confirm] result is cached by id. [food] serves the cache first on a network
 * failure, and [cachedFood] is a network-free seed. Plain local cache, not a mirror.
 */
@Singleton
class FoodRepository @Inject constructor(
    private val api: FoodApi,
    private val cacheDao: CatalogCacheDao,
    moshi: Moshi,
) {
    private val foodAdapter = moshi.adapter(Food::class.java)

    suspend fun search(query: String): List<Food> =
        api.search(query).also { cache(it) }

    /**
     * food-search-local-first: name search over the catalog foods already cached on
     * this device — served with zero network so the add-food list populates the
     * instant the user types. Covers the foods the user actually fetches/logs (the
     * cache warms with every [search]/[food]/[create] result and [warmFromIds]);
     * the network [search] augments it with the long tail. Returns [] for a blank
     * query. [query] is lowercased + LIKE-sanitized here.
     */
    suspend fun localSearch(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Food> {
        val q = query.trim().lowercase().replace("%", "").replace("_", "")
        if (q.isBlank()) return emptyList()
        return cacheDao.search(CACHE_TYPE, q, limit).mapNotNull { decode(it.json) }
    }

    /**
     * food-search-local-first (seed): best-effort warm of the local catalog cache
     * for [foodIds] the user has logged but may not have fetched as full foods yet
     * (so name search finds them). Only fetches ids not already cached, and swallows
     * per-id failures — this is a background convenience, never load-bearing.
     */
    suspend fun warmFromIds(foodIds: Collection<String>) {
        foodIds.distinct()
            .filter { cacheDao.getById(CACHE_TYPE, it) == null }
            .forEach { id -> runCatching { food(id) } }
    }

    /**
     * Cache-first detail read: revalidate over the network and refresh the cache;
     * on failure fall back to the last-fetched cached copy so re-entry works offline.
     */
    suspend fun food(foodId: String): Food =
        runCatching { api.getFood(foodId).also { cache(it) } }
            .getOrElse { e -> cachedFood(foodId) ?: throw e }

    /**
     * offline-fix: network-free seed from the local catalog cache — never hits the
     * network. Returns null when the food hasn't been fetched before.
     */
    suspend fun cachedFood(foodId: String): Food? =
        cacheDao.getById(CACHE_TYPE, foodId)?.let { decode(it.json) }

    /**
     * Resolve a scanned barcode. Returns null on a 404 (truly unknown product,
     * even after the backend's Open Food Facts fallback) so the caller can offer
     * the label-photo path; other errors propagate.
     */
    suspend fun barcodeLookup(code: String): Food? =
        try {
            api.barcodeLookup(code).also { cache(it) }
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }

    /**
     * Create a catalog food. [idempotencyKey] + the client-minted [FoodCreateRequest.id]
     * make a replay from the durable op worker safe: the backend returns the same
     * food instead of duplicating it (and re-running image generation).
     */
    suspend fun create(body: FoodCreateRequest, idempotencyKey: String): Food =
        api.create(body, idempotencyKey).also { cache(it) }

    suspend fun confirm(foodId: String): Food = api.confirm(foodId).also { cache(it) }

    // ---- catalog cache (offline-fix) ----

    private suspend fun cache(food: Food) {
        cacheDao.upsert(food.toCacheEntity(System.currentTimeMillis()))
    }

    private suspend fun cache(foods: List<Food>) {
        if (foods.isEmpty()) return
        val now = System.currentTimeMillis()
        cacheDao.upsertAll(foods.map { it.toCacheEntity(now) })
    }

    // food-search-local-first: carry the denormalized lowercase name/brand so the
    // cached row is name-searchable via CatalogCacheDao.search.
    private fun Food.toCacheEntity(now: Long) = CatalogCacheEntity(
        type = CACHE_TYPE,
        id = foodId,
        json = foodAdapter.toJson(this),
        updatedAt = now,
        nameLower = name.lowercase(),
        brandLower = brand?.lowercase(),
    )

    private fun decode(json: String): Food? =
        runCatching { foodAdapter.fromJson(json) }.getOrNull()

    private companion object {
        const val CACHE_TYPE = "food"
        const val DEFAULT_SEARCH_LIMIT = 25
    }
}
