package com.gte619n.healthfitness.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gte619n.healthfitness.data.db.entity.WorkoutSessionDraftEntity
import kotlinx.coroutines.flow.Flow

/**
 * ADR-0012 (IMPL-AND-16) — accessor for the device-local workout-session
 * drafts. Unlike the mirror DAOs there is no archive/tombstone path: a draft
 * is hard-deleted when it is finished, skipped, discarded, or swept stale.
 */
@Dao
interface WorkoutSessionDraftDao {
    /** Reactive draft for one scheduled session — the logger UI's source of truth. */
    @Query(
        "SELECT * FROM workoutSessionDrafts " +
            "WHERE programId = :programId AND scheduledId = :scheduledId",
    )
    fun observe(programId: String, scheduledId: String): Flow<WorkoutSessionDraftEntity?>

    /** Every draft, newest started first (resume affordances). */
    @Query("SELECT * FROM workoutSessionDrafts ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<WorkoutSessionDraftEntity>>

    @Query(
        "SELECT * FROM workoutSessionDrafts " +
            "WHERE programId = :programId AND scheduledId = :scheduledId",
    )
    suspend fun getByKey(programId: String, scheduledId: String): WorkoutSessionDraftEntity?

    /**
     * Drafts idle strictly longer than the cutoff (`lastActivityAt < cutoff`),
     * for the ADR-0012 Decision 4 stale sweep. The [androidx.room.Index] on
     * `lastActivityAt` backs the scan.
     */
    @Query("SELECT * FROM workoutSessionDrafts WHERE lastActivityAt < :cutoff")
    suspend fun listIdleBefore(cutoff: Long): List<WorkoutSessionDraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WorkoutSessionDraftEntity)

    @Query(
        "DELETE FROM workoutSessionDrafts " +
            "WHERE programId = :programId AND scheduledId = :scheduledId",
    )
    suspend fun delete(programId: String, scheduledId: String)

    @Query("DELETE FROM workoutSessionDrafts")
    suspend fun clear()
}
