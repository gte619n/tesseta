package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.db.dao.NutritionOpDao
import com.gte619n.healthfitness.data.db.entity.NutritionOpEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence facade over the durable nutrition-op queue ([NutritionOpEntity]).
 *
 * This replaces the in-memory `PendingCaptureStore`: the rows live in Room, so a
 * synthetic "logging…" placeholder — and the operation itself — survives process
 * death. `NutritionOpWorker` drains it; the UI observes [observeAll] to render the
 * placeholders.
 */
@Singleton
class NutritionOpStore @Inject constructor(private val dao: NutritionOpDao) {

    /** All in-flight ops, oldest first — the UI's synthetic-row source. */
    fun observeAll(): Flow<List<NutritionOpEntity>> = dao.observeAll()

    suspend fun add(op: NutritionOpEntity) = dao.insert(op)

    suspend fun find(id: String): NutritionOpEntity? = dao.findById(id)

    suspend fun listDue(now: Long): List<NutritionOpEntity> = dao.listDue(now)

    suspend fun recordFailure(id: String, attempts: Int, nextAttemptAt: Long) =
        dao.recordFailure(id, attempts, nextAttemptAt)

    suspend fun remove(id: String) = dao.deleteById(id)
}
