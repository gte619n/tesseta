package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.db.dao.NutritionEntryDao
import com.gte619n.healthfitness.data.db.dao.NutritionTargetDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.nutrition.CompositeMealRequest
import com.gte619n.healthfitness.domain.nutrition.DailyRollup
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.domain.nutrition.UpdateIngredientRequest
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — Room-backed, offline-first nutrition repository.
 *
 * Nutrition is **date-keyed**. Logged entries are user writes mirrored per-entry
 * into the `nutritionEntries` table under a composite id `"<date>/<entryId>"` so
 * the outbox can route a replay to `api/me/nutrition/{date}/entries` (see
 * [com.gte619n.healthfitness.data.sync.OutboxEndpointRegistry]). [day] reassembles
 * the [NutritionDay] from the mirrored entries for that date, re-deriving the
 * meal-group subtotals and day totals client-side — so the day renders from Room
 * offline (D8) and an optimistic add/edit/delete appears instantly as PENDING (D7).
 *
 * **Server-derived (D9), kept pull-only:**
 *  - The `range` daily rollups (`nutritionDailyLogs` aggregates) read live.
 *  - The composite-meal create + per-ingredient re-portion drive AI image
 *    generation; they stay on the network path and refresh the date's mirror after.
 *  - The macro `target` is mirrored (`nutritionTargets`) and its write is optimistic.
 *
 * Each entry's stored [Entry.macros] is the frozen full-portion snapshot the
 * backend persists (the ViewModel computes `macrosPer100g.forPortion(...)` before
 * logging), so the client totals are a straight sum of the entry snapshots — the
 * same arithmetic the server does.
 */
@Singleton
class NutritionRepository @Inject constructor(
    private val api: NutritionApi,
    private val entryDao: NutritionEntryDao,
    private val targetDao: NutritionTargetDao,
    private val support: MirrorRepositorySupport,
    moshi: Moshi,
) {
    private val rowAdapter = moshi.adapter(NutritionEntryRow::class.java)
    private val macrosAdapter = moshi.adapter(Macros::class.java)

    /** Mirror payload for one logged entry: the entry plus the date it belongs to. */
    data class NutritionEntryRow(val date: String, val entry: Entry)

    suspend fun day(date: String): NutritionDay {
        if (support.killSwitchOn()) return api.getDay(date)
        // Fill this date's entries + target from the network if we have nothing yet,
        // then assemble the day from the mirror so it renders offline.
        if (entriesForDate(date).isEmpty()) fillDay(date)
        return assembleDay(date)
    }

    suspend fun addEntry(date: String, body: EntryRequest): Entry {
        val entryId = UUID.randomUUID().toString()
        val entry = Entry(
            entryId = entryId,
            meal = body.meal,
            foodId = body.foodId,
            foodName = body.foodName,
            servingLabel = body.servingLabel,
            servingGrams = body.servingGrams,
            quantity = body.quantity,
            macros = body.macros,
            source = body.source,
        )
        support.createLocal(
            table = MirrorTables.NUTRITION_ENTRIES,
            id = composite(date, entryId),
            payloadJson = rowAdapter.toJson(NutritionEntryRow(date, entry)),
            lastUpdate = System.currentTimeMillis(),
        )
        return entry
    }

    suspend fun patchEntry(date: String, entryId: String, body: EntryPatchRequest): Entry {
        val current = entriesForDate(date).firstOrNull { it.entryId == entryId }
            ?: return api.patchEntry(date, entryId, body)
        val merged = current.copy(
            meal = body.meal ?: current.meal,
            foodName = body.foodName ?: current.foodName,
            servingLabel = body.servingLabel ?: current.servingLabel,
            servingGrams = body.servingGrams ?: current.servingGrams,
            quantity = body.quantity ?: current.quantity,
            macros = body.macros ?: current.macros,
        )
        support.updateLocal(
            table = MirrorTables.NUTRITION_ENTRIES,
            id = composite(date, entryId),
            payloadJson = rowAdapter.toJson(NutritionEntryRow(date, merged)),
            lastUpdate = System.currentTimeMillis(),
        )
        return merged
    }

    suspend fun deleteEntry(date: String, entryId: String) {
        support.deleteLocal(
            MirrorTables.NUTRITION_ENTRIES,
            composite(date, entryId),
            System.currentTimeMillis(),
        )
    }

    suspend fun addCompositeMeal(date: String, body: CompositeMealRequest): Entry {
        // AI image generation (online-only per D17): create on the network, then
        // refresh this date's mirror so the new composite entry renders from Room.
        val entry = api.addCompositeMeal(date, body)
        fillDay(date)
        return entry
    }

    suspend fun updateIngredient(
        date: String,
        entryId: String,
        index: Int,
        body: UpdateIngredientRequest,
    ): Entry {
        // Re-portioning a composite ingredient re-runs server-side macro math; keep
        // it on the network and refresh the mirror for the date.
        val entry = api.updateIngredient(date, entryId, index, body)
        fillDay(date)
        return entry
    }

    /** Returns the active macro target, or null when the server replies 204. */
    suspend fun target(): Macros? {
        if (support.killSwitchOn()) {
            val response = api.getTarget()
            return if (response.code() == 204) null else response.body()
        }
        mirroredTarget()?.let { return it }
        val response = api.getTarget()
        val body = if (response.code() == 204) null else response.body()
        if (body != null) refreshTarget(body)
        return body
    }

    suspend fun setTarget(target: Macros): Macros {
        support.updateLocal(
            table = MirrorTables.NUTRITION_TARGETS,
            id = TARGET_ID,
            payloadJson = macrosAdapter.toJson(target),
            lastUpdate = System.currentTimeMillis(),
        )
        return target
    }

    /** Server-derived daily rollups (`nutritionDailyLogs`, D9) — pull-only. */
    suspend fun range(from: String, to: String): List<DailyRollup> =
        api.getRange(from, to)

    // ---- mirror assembly --------------------------------------------------

    private suspend fun fillDay(date: String) {
        val day = api.getDay(date)
        val entries = day.meals.flatMap { it.entries }
        support.refreshInto(
            MirrorTables.NUTRITION_ENTRIES,
            entries.map { entry ->
                MirrorRepositorySupport.RefreshRow(
                    id = composite(date, entry.entryId),
                    payloadJson = rowAdapter.toJson(NutritionEntryRow(date, entry)),
                    lastUpdate = System.currentTimeMillis(),
                )
            },
        )
        day.target?.let { refreshTarget(it) }
    }

    private suspend fun assembleDay(date: String): NutritionDay {
        val entries = entriesForDate(date)
        val groups = Meal.entries.mapNotNull { meal ->
            val mealEntries = entries.filter { it.meal.equals(meal.wire, ignoreCase = true) }
            if (mealEntries.isEmpty()) null
            else MealGroup(
                meal = meal.wire,
                subtotal = sumMacros(mealEntries.map { it.macros }),
                entries = mealEntries,
            )
        }
        return NutritionDay(
            date = date,
            totals = sumMacros(entries.map { it.macros }),
            target = mirroredTarget(),
            meals = groups,
        )
    }

    private suspend fun entriesForDate(date: String): List<Entry> =
        entryDao.observeActive().first()
            // #40: carry the mirror row's syncState onto the entry for the per-row
            // PENDING/FAILED SyncBadge.
            .mapNotNull { row ->
                runCatching { rowAdapter.fromJson(row.payloadJson) }.getOrNull()
                    ?.takeIf { it.date == date }
                    ?.let { it.entry.copy(syncState = row.syncState) }
            }

    private suspend fun mirroredTarget(): Macros? =
        targetDao.observeActive().first().firstOrNull()
            ?.let { runCatching { macrosAdapter.fromJson(it.payloadJson) }.getOrNull() }

    private suspend fun refreshTarget(target: Macros) {
        support.refreshInto(
            MirrorTables.NUTRITION_TARGETS,
            listOf(
                MirrorRepositorySupport.RefreshRow(
                    id = TARGET_ID,
                    payloadJson = macrosAdapter.toJson(target),
                    lastUpdate = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private fun sumMacros(items: List<Macros>): Macros = items.fold(Macros.EMPTY) { acc, m ->
        Macros(
            caloriesKcal = acc.caloriesKcal plusN m.caloriesKcal,
            proteinGrams = acc.proteinGrams plusN m.proteinGrams,
            carbsGrams = acc.carbsGrams plusN m.carbsGrams,
            fatGrams = acc.fatGrams plusN m.fatGrams,
            fiberGrams = acc.fiberGrams plusN m.fiberGrams,
            sugarGrams = acc.sugarGrams plusN m.sugarGrams,
        )
    }

    private infix fun Double?.plusN(other: Double?): Double? =
        if (this == null && other == null) null else (this ?: 0.0) + (other ?: 0.0)

    private fun composite(date: String, entryId: String) = "$date/$entryId"

    private companion object {
        const val TARGET_ID = "target"
    }
}
