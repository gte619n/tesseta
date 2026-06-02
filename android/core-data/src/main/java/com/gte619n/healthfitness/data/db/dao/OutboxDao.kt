package com.gte619n.healthfitness.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gte619n.healthfitness.data.db.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-AND-20 (Phase 3) — the offline write queue accessor (D7).
 *
 * Phase 4's `OutboxRepository` uses these to enqueue mutations, observe/list
 * the rows that are due to drain (`nextAttemptAt <= now`), inspect the per-entity
 * mutation chain in `seq` order (for the reducer / collapse), and remove rows
 * once they have been confirmed by the server.
 */
@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: OutboxEntity)

    /** All mutations whose backoff window has elapsed, oldest first (drain order). */
    @Query("SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY seq ASC")
    suspend fun listDue(now: Long): List<OutboxEntity>

    /** Reactive count of pending mutations — feeds the global sync indicator (D11). */
    @Query("SELECT COUNT(*) FROM outbox")
    fun observePendingCount(): Flow<Int>

    /** The mutation chain for one entity, in `seq` order (reducer input, D7). */
    @Query("SELECT * FROM outbox WHERE entityId = :entityId ORDER BY seq ASC")
    suspend fun listByEntity(entityId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY seq ASC")
    suspend fun listAll(): List<OutboxEntity>

    /** Highest seq issued so far; the enqueue path uses `+1` for per-entity ordering. */
    @Query("SELECT MAX(seq) FROM outbox")
    suspend fun maxSeq(): Long?

    @Query("UPDATE outbox SET attempts = :attempts, nextAttemptAt = :nextAttemptAt WHERE mutationId = :mutationId")
    suspend fun recordFailure(mutationId: String, attempts: Int, nextAttemptAt: Long)

    @Query("DELETE FROM outbox WHERE mutationId = :mutationId")
    suspend fun deleteById(mutationId: String)

    @Query("DELETE FROM outbox WHERE entityId = :entityId")
    suspend fun deleteByEntity(entityId: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}
