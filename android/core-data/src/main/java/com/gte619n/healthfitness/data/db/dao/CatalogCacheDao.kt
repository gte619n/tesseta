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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CatalogCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CatalogCacheEntity>)
}
