package com.gte619n.healthfitness.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gte619n.healthfitness.data.db.entity.CatalogCacheEntity

/**
 * offline-fix — DAO for the shared [CatalogCacheEntity] read cache (ADR-0018).
 *
 * Reads/writes are keyed by the `(type, id)` composite PK so one table serves the
 * equipment / food / drug catalogs. `upsert`/`upsertAll` REPLACE-on-conflict so a
 * re-fetch refreshes the cached row in place. This is a plain cache: no tombstones,
 * no sync state — the catalog repos just upsert what they fetch and read it back.
 */
@Dao
interface CatalogCacheDao {

    @Query("SELECT * FROM catalog_cache WHERE type = :type AND id = :id")
    suspend fun getById(type: String, id: String): CatalogCacheEntity?

    @Query("SELECT * FROM catalog_cache WHERE type = :type ORDER BY updatedAt DESC")
    suspend fun getByType(type: String): List<CatalogCacheEntity>

    /**
     * food-search-local-first: name/brand search over the cached rows of one
     * [type], ranked to roughly mirror the backend's relevance order — exact name,
     * then name-prefix, then word-boundary match, then any substring, then
     * brand-only hits — and alphabetical within a tier. [q] must be lowercased and
     * LIKE-sanitized by the caller (no `%`/`_`). Rows with a null [nameLower]
     * (pre-v7, not yet re-cached) never match.
     */
    @Query(
        "SELECT * FROM catalog_cache WHERE type = :type AND nameLower IS NOT NULL AND " +
            "(nameLower LIKE '%' || :q || '%' OR brandLower LIKE '%' || :q || '%') " +
            "ORDER BY CASE " +
            "WHEN nameLower = :q THEN 0 " +
            "WHEN nameLower LIKE :q || '%' THEN 1 " +
            "WHEN nameLower LIKE '% ' || :q || '%' THEN 2 " +
            "WHEN nameLower LIKE '%' || :q || '%' THEN 3 " +
            "ELSE 4 END, nameLower LIMIT :limit",
    )
    suspend fun search(type: String, q: String, limit: Int): List<CatalogCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CatalogCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CatalogCacheEntity>)

    /**
     * Evict one cached row. IMPL-DRINK-01 uses this to drop a drink the server no
     * longer returns (archived / deleted on web) when a warm replaces the cache.
     */
    @Query("DELETE FROM catalog_cache WHERE type = :type AND id = :id")
    suspend fun deleteById(type: String, id: String)
}
