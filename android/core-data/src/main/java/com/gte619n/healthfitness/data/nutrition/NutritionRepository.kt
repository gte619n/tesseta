package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.DailyRollup
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import javax.inject.Inject
import javax.inject.Singleton

// Thin wrapper over NutritionApi (mirrors GoalsRepository). Networking errors
// propagate as exceptions; ViewModels map them to UI error state.
@Singleton
class NutritionRepository @Inject constructor(
    private val api: NutritionApi,
) {
    suspend fun day(date: String): NutritionDay = api.getDay(date)

    suspend fun addEntry(date: String, body: EntryRequest): Entry =
        api.addEntry(date, body)

    suspend fun patchEntry(date: String, entryId: String, body: EntryPatchRequest): Entry =
        api.patchEntry(date, entryId, body)

    suspend fun deleteEntry(date: String, entryId: String) {
        api.deleteEntry(date, entryId)
    }

    /** Returns the active macro target, or null when the server replies 204. */
    suspend fun target(): Macros? {
        val response = api.getTarget()
        return if (response.code() == 204) null else response.body()
    }

    suspend fun setTarget(target: Macros): Macros = api.putTarget(target)

    suspend fun range(from: String, to: String): List<DailyRollup> =
        api.getRange(from, to)
}
