package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.db.dao.NutritionEntryDao
import com.gte619n.healthfitness.data.db.dao.NutritionTargetDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.nutrition.CompositeMealRequest
import com.gte619n.healthfitness.domain.nutrition.DailyRollup
import com.gte619n.healthfitness.domain.nutrition.DescribeMealLogRequest
import com.gte619n.healthfitness.domain.nutrition.DescribeMealRequest
import com.gte619n.healthfitness.domain.nutrition.DescribedMeal
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryIngredient
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.domain.nutrition.RelogRequest
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
    private val syncDocAdapter = moshi.adapter(NutritionEntrySyncDoc::class.java)

    /** Mirror payload for one logged entry: the entry plus the date it belongs to. */
    data class NutritionEntryRow(val date: String, val entry: Entry)

    /**
     * The OTHER shape a `nutritionEntries` mirror row can take: the raw server
     * document the generic sync engine writes (every other table stores the flat
     * doc; only nutrition wraps it in [NutritionEntryRow]). The sync delta is the
     * persisted Firestore entry, so its image fields are `mealImageUrl`/
     * `mealImageStatus` — the REST read path renames those to `imageUrl`/
     * `imageStatus`. Without handling this shape a sync-written row failed to parse
     * and the entry vanished from the day until a REST `fillDay` rewrote it. We
     * map it back to a domain [Entry] so a sync delta is usable directly. The
     * entryId isn't a field on the doc — it's the trailing segment of the mirror
     * row id (`"<date>/<entryId>"`).
     */
    private data class NutritionEntrySyncDoc(
        val date: String?,
        val meal: String?,
        val foodId: String?,
        val foodName: String?,
        val servingLabel: String?,
        val servingGrams: Double?,
        val quantity: Double?,
        val macros: Macros?,
        val source: String?,
        val ingredients: List<EntryIngredient>?,
        val mealImageUrl: String?,
        val mealImageStatus: String?,
        val analysisStatus: String?,
    ) {
        fun toEntry(entryId: String): Entry = Entry(
            entryId = entryId,
            meal = meal ?: "",
            foodId = foodId,
            foodName = foodName ?: "",
            servingLabel = servingLabel,
            servingGrams = servingGrams,
            quantity = quantity ?: 1.0,
            macros = macros ?: Macros.EMPTY,
            source = source ?: "",
            imageUrl = mealImageUrl,
            imageStatus = mealImageStatus ?: "NONE",
            analysisStatus = analysisStatus ?: "NONE",
            ingredients = ingredients,
        )
    }

    suspend fun day(date: String): NutritionDay {
        if (support.killSwitchOn()) return api.getDay(date)
        // Offline-first: serve from the mirror. But re-pull from the REST day when
        // either (a) we have nothing mirrored yet, or (b) an entry is still
        // settling — a photo still ANALYZING or an image still PENDING. The mirror
        // only learns of the server-side finalize (ANALYZING→READY, image ready)
        // via a sync delta, which can lag or miss, so polling day() would
        // otherwise just re-read the stale placeholder forever. Re-fetching while
        // settling is the same source the web reads and converges the row.
        val local = entriesForDate(date)
        val settling = local.any { it.isAnalyzing || it.imageStatus == "PENDING" }
        if (local.isEmpty() || settling) fillDay(date)
        return assembleDay(date)
    }

    /**
     * Force this date's entries + target to be re-pulled from the network into
     * the mirror, even when the date already has rows. Photo capture creates the
     * ANALYZING entry server-side only (multipart, network path), so without this
     * it never lands locally — and day() won't re-fetch once the date is
     * non-empty, so the placeholder would never appear (and the settle-poll would
     * exit immediately). Mirrors addCompositeMeal's post-create fillDay.
     */
    suspend fun refreshDay(date: String) {
        if (support.killSwitchOn()) return
        fillDay(date)
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

    /**
     * Resolve a free-text description to a saved meal (a previous match, or a
     * freshly created+saved one). Pure network read — nothing is logged or
     * mirrored yet; the UI previews it, then logs via [logDescribedMeal].
     */
    suspend fun describeMeal(description: String): DescribedMeal =
        api.describeMeal(DescribeMealRequest(description))

    suspend fun logDescribedMeal(date: String, mealId: String, meal: String): Entry {
        // Like addCompositeMeal: drives AI image work (online-only per D17), so
        // create on the network, then refresh this date's mirror to render it.
        val entry = api.logDescribedMeal(
            date,
            DescribeMealLogRequest(mealId = mealId, meal = meal),
        )
        fillDay(date)
        return entry
    }

    /**
     * Fire-and-forget describe: the server logs an ANALYZING placeholder named
     * with the description and resolves it in the background (the camera
     * pattern). The quick 202 POST is the only wait; the mirror refresh pulls
     * the placeholder so the day renders it immediately and the settle-poll
     * takes over. Online-only (AI flow, D17).
     */
    suspend fun describeMealAsync(date: String, description: String, meal: String): Entry {
        val entry = api.describeMealAsync(
            date,
            DescribeMealLogRequest(description = description, meal = meal),
        )
        fillDay(date)
        return entry
    }

    /** Recent distinct foods/meals for the add-flow's one-tap list. Live read. */
    suspend fun recentMeals(days: Int = 14, limit: Int = 20): List<Entry> =
        api.recentMeals(days, limit)

    /**
     * One-tap re-log of a recent entry onto [date]. Server-side copy (reuses
     * catalog foods + images — no AI rework), then refresh the date's mirror so
     * the new row renders from Room.
     */
    suspend fun relog(date: String, sourceDate: String, sourceEntryId: String, meal: String): Entry {
        val entry = api.relog(date, RelogRequest(sourceDate, sourceEntryId, meal))
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
        val now = System.currentTimeMillis()
        val serverIds = entries.map { composite(date, it.entryId) }.toSet()
        support.refreshInto(
            MirrorTables.NUTRITION_ENTRIES,
            entries.map { entry ->
                MirrorRepositorySupport.RefreshRow(
                    id = composite(date, entry.entryId),
                    payloadJson = rowAdapter.toJson(NutritionEntryRow(date, entry)),
                    lastUpdate = now,
                )
            },
        )
        // Reconcile: drop any local row for THIS date the server no longer has, so
        // a deletion elsewhere — or an orphaned placeholder the server cleaned up —
        // stops lingering. refreshInto only upserts; it never prunes. Local-only
        // (no outbox); dirty optimistic creates are preserved by pruneLocal.
        val staleIds = entriesForDate(date)
            .map { composite(date, it.entryId) }
            .filterNot { it in serverIds }
        support.pruneLocal(MirrorTables.NUTRITION_ENTRIES, staleIds, now)
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
                // REST envelope {date, entry} — the shape day()/fillDay write.
                runCatching { rowAdapter.fromJson(row.payloadJson) }.getOrNull()
                    ?.takeIf { it.date == date }
                    ?.let { return@mapNotNull it.entry.copy(syncState = row.syncState) }
                // Flat server doc — the shape the generic sync engine writes. The
                // entryId lives in the row id ("<date>/<entryId>"), not the doc.
                runCatching { syncDocAdapter.fromJson(row.payloadJson) }.getOrNull()
                    ?.takeIf { it.date == date }
                    ?.let {
                        return@mapNotNull it
                            .toEntry(row.id.substringAfter('/', row.id))
                            .copy(syncState = row.syncState)
                    }
                null
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
