package com.gte619n.healthfitness.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gte619n.healthfitness.data.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-AND-20 (Phase 3) — accessor for the single-row [SyncStateEntity] (D6/D13).
 */
@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("UPDATE sync_state SET cursor = :cursor WHERE id = 0")
    suspend fun updateCursor(cursor: String?)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}
