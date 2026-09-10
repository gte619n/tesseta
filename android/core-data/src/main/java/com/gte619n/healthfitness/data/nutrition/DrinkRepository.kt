package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.db.dao.CatalogCacheDao
import com.gte619n.healthfitness.data.db.entity.CatalogCacheEntity
import com.gte619n.healthfitness.domain.nutrition.Food
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-DRINK-01 (Phase 2) — the offline-first source for the user's personal
 * drink catalog on the Drink card.
 *
 * `foodCatalog` does NOT sync to Android (it's absent from CollectionRegistry /
 * the sync change reader), so a bar with no signal can't lazily fetch drinks.
 * Instead this repo PROACTIVELY WARMS the drinks into the shared [CatalogCacheDao]
 * (`type = [DRINK_CACHE_TYPE]`) from `GET /api/me/drinks`, and serves the list +
 * name search entirely from that cache. Warm on Drink Mode enable, session start,
 * and app foreground (§3.2 / §4.3) — after any one warm, tiles/search work fully
 * offline until the next warm refreshes them.
 *
 * The cached JSON is the full [Food] (including its [Food.alcohol] block), so the
 * card renders std-drink counts and freezes the alcohol snapshot at log time with
 * zero network. Recents come from the synced `nutritionEntries` mirror
 * ([NutritionRepository.cachedRecentDrinks]), not from here.
 */
@Singleton
class DrinkRepository @Inject constructor(
    private val api: DrinkApi,
    private val cacheDao: CatalogCacheDao,
    moshi: Moshi,
) {
    private val foodAdapter = moshi.adapter(Food::class.java)

    /**
     * Fetch my drinks from the network and overwrite the local `type="drink"`
     * cache. Best-effort: on any failure the previously-warmed cache is kept, so
     * calling this on every foreground never regresses offline availability.
     * Returns the freshly-warmed list (or the cached list on failure) so a caller
     * can render immediately.
     */
    suspend fun warm(): List<Food> =
        runCatching { api.myDrinks() }
            .onSuccess { replaceCache(it) }
            .getOrElse { cachedDrinks() }

    /**
     * All my drinks from the local cache — newest-warmed first — with zero
     * network. Empty until the first successful [warm]; the card degrades to an
     * empty-with-message state, never a crash (§6.1 "un-warmed cache").
     */
    suspend fun cachedDrinks(): List<Food> =
        cacheDao.getByType(DRINK_CACHE_TYPE).mapNotNull { decode(it.json) }

    /**
     * Name search over the warmed drink cache (zero network). [query] is lowercased
     * + LIKE-sanitized here; a blank query returns [cachedDrinks] so the full list
     * shows before the user types.
     */
    suspend fun searchDrinks(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Food> {
        val q = query.trim().lowercase().replace("%", "").replace("_", "")
        if (q.isBlank()) return cachedDrinks().take(limit)
        return cacheDao.search(DRINK_CACHE_TYPE, q, limit).mapNotNull { decode(it.json) }
    }

    /** One cached drink by id (for freezing its snapshot when logging). */
    suspend fun cachedDrink(foodId: String): Food? =
        cacheDao.getById(DRINK_CACHE_TYPE, foodId)?.let { decode(it.json) }

    // ---- cache plumbing ----

    /**
     * Replace the drink cache with the freshly-fetched list: upsert every fetched
     * drink, then evict any cached drink the server no longer returns (archived /
     * deleted on web) so it drops off the card. Ordered so the newest-first server
     * order is preserved by `updatedAt` (later index ⇒ smaller updatedAt).
     */
    private suspend fun replaceCache(drinks: List<Food>) {
        val now = System.currentTimeMillis()
        if (drinks.isNotEmpty()) {
            // Descending updatedAt by server order so getByType (updatedAt DESC)
            // keeps the server's newest-first ordering.
            cacheDao.upsertAll(
                drinks.mapIndexed { i, d -> d.toCacheEntity(now - i) },
            )
        }
        val keep = drinks.map { it.foodId }.toSet()
        val stale = cacheDao.getByType(DRINK_CACHE_TYPE).map { it.id }.filterNot { it in keep }
        stale.forEach { cacheDao.deleteById(DRINK_CACHE_TYPE, it) }
    }

    private fun Food.toCacheEntity(updatedAt: Long) = CatalogCacheEntity(
        type = DRINK_CACHE_TYPE,
        id = foodId,
        json = foodAdapter.toJson(this),
        updatedAt = updatedAt,
        nameLower = name.lowercase(),
        brandLower = brand?.lowercase(),
    )

    private fun decode(json: String): Food? =
        runCatching { foodAdapter.fromJson(json) }.getOrNull()

    private companion object {
        // Shares the catalog_cache table, namespaced by this type; distinct from
        // FoodRepository's "food" rows and NutritionRepository's "meal" rows. No
        // Room migration needed — the alcohol facts ride inside the opaque JSON.
        const val DRINK_CACHE_TYPE = "drink"
        const val DEFAULT_SEARCH_LIMIT = 50
    }
}
