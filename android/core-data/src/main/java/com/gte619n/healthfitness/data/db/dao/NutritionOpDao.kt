package com.gte619n.healthfitness.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gte619n.healthfitness.data.db.entity.NutritionOpEntity
import kotlinx.coroutines.flow.Flow

/**
 * Accessor for the durable nutrition-op queue ([NutritionOpEntity]). The
 * `NutritionOpWorker` lists due rows to execute; the UI observes all rows to
 * render synthetic "logging…" placeholders that survive process death.
 */
@Dao
interface NutritionOpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: NutritionOpEntity)

    /** All ops whose backoff window has elapsed, oldest first (drain order). */
    @Query("SELECT * FROM nutritionOps WHERE nextAttemptAt <= :now ORDER BY createdAt ASC")
    suspend fun listDue(now: Long): List<NutritionOpEntity>

    /** One op by id (the worker reloads its full payload from here). */
    @Query("SELECT * FROM nutritionOps WHERE id = :id")
    suspend fun findById(id: String): NutritionOpEntity?

    /** Reactive list of all in-flight ops, oldest first — feeds the synthetic rows. */
    @Query("SELECT * FROM nutritionOps ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<NutritionOpEntity>>

    @Query("UPDATE nutritionOps SET attempts = :attempts, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun recordFailure(id: String, attempts: Int, nextAttemptAt: Long)

    @Query("DELETE FROM nutritionOps WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM nutritionOps")
    suspend fun clear()
}
