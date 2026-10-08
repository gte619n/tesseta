package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.EntryIngredient
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * IMPL-IOS-01 (logging spine) — online-first [NutritionDayRepository] over the
 * EXISTING backend nutrition endpoints (the same ones Android's `NutritionApi`
 * hits — paths matched 1:1). Follows the reference `HttpMedicationRepository`
 * shape: a per-date [MutableStateFlow] that lazily fetches on first observe and
 * re-fetches after every mutation, so the Today screen is reactive without the
 * offline mirror/outbox (that durable sync layer lands later).
 *
 * Every mutation calls the API and then [day] (which both re-fetches and pushes
 * into the observed flow), so the backend's authoritative entry — not a
 * client-side recompute — drives the UI. The composite-editing, multipart
 * leftover-analyze, and durable-capture paths are NOT reachable from the wired
 * Today spine yet; they throw a descriptive [NotImplementedError] until the op
 * rail + edit sheets are built (marked TODO below).
 */
class HttpNutritionDayRepository(private val client: HttpClient) : NutritionDayRepository {

    // One reactive slot per date. getOrPut keeps a stable flow instance so the
    // VM's observeDay collection sees every post-mutation re-fetch.
    private val days = mutableMapOf<String, MutableStateFlow<NutritionDay?>>()

    private fun slot(date: String): MutableStateFlow<NutritionDay?> =
        days.getOrPut(date) { MutableStateFlow(null) }

    // ---- Reactive + imperative reads -------------------------------------

    override fun observeDay(date: String): Flow<NutritionDay> =
        slot(date)
            .onStart { if (slot(date).value == null) refreshDay(date) }
            .filterNotNull()

    override suspend fun day(date: String): NutritionDay =
        client.get("api/me/nutrition/$date").body<NutritionDay>().also { slot(date).value = it }

    /** No local mirror yet — the last network value, or null on a cold screen. */
    override suspend fun cachedDay(date: String): NutritionDay? = slot(date).value

    override suspend fun refreshDay(date: String) {
        slot(date).value = client.get("api/me/nutrition/$date").body()
    }

    // ---- Target -----------------------------------------------------------

    override suspend fun target(): Macros? {
        // GET returns 204 No Content when the user has no target set; body() on an
        // empty 204 would throw, so branch on the status (expectSuccess lets 204 pass).
        val resp: HttpResponse = client.get("api/me/nutrition/target")
        return if (resp.status == HttpStatusCode.NoContent) null else resp.body()
    }

    override suspend fun setTarget(target: Macros): Macros =
        client.put("api/me/nutrition/target") { setBody(target) }.body()

    // ---- Entry mutations --------------------------------------------------

    override suspend fun addEntry(date: String, body: EntryRequest): Entry {
        val entry = client.post("api/me/nutrition/$date/entries") { setBody(body) }.body<Entry>()
        refreshDay(date)
        return entry
    }

    override suspend fun patchEntry(date: String, entryId: String, body: EntryPatchRequest): Entry {
        val entry = client.patch("api/me/nutrition/$date/entries/$entryId") { setBody(body) }.body<Entry>()
        refreshDay(date)
        return entry
    }

    override suspend fun deleteEntry(date: String, entryId: String) {
        client.delete("api/me/nutrition/$date/entries/$entryId")
        refreshDay(date)
    }

    // ---- Image self-heal --------------------------------------------------

    override suspend fun regenerateEntryImage(date: String, entryId: String): Entry {
        val entry = client.post("api/me/nutrition/$date/entries/$entryId/image/regenerate").body<Entry>()
        refreshDay(date)
        return entry
    }

    override suspend fun reanalyzeEntry(date: String, entryId: String): Entry {
        val entry = client.post("api/me/nutrition/$date/entries/$entryId/reanalyze").body<Entry>()
        refreshDay(date)
        return entry
    }

    // ---- Adjust with AI (async) -------------------------------------------

    override suspend fun submitAdjust(date: String, entryId: String, instruction: String, saveAsMeal: Boolean) {
        client.post("api/me/nutrition/$date/entries/$entryId/adjust/start") {
            setBody(AdjustStartRequest(instruction = instruction, saveAsMeal = saveAsMeal))
        }
        refreshDay(date)
    }

    override suspend fun commitAdjust(date: String, entryId: String, saveAsMeal: Boolean?): Entry {
        val entry = client.post("api/me/nutrition/$date/entries/$entryId/adjust/commit") {
            setBody(AdjustCommitRequest(saveAsMeal = saveAsMeal))
        }.body<Entry>()
        refreshDay(date)
        return entry
    }

    override suspend fun discardAdjust(date: String, entryId: String): Entry {
        val entry = client.post("api/me/nutrition/$date/entries/$entryId/adjust/discard").body<Entry>()
        refreshDay(date)
        return entry
    }

    // ---- Remove leftovers -------------------------------------------------

    override suspend fun analyzeLeftovers(date: String, entryId: String, jpeg: ByteArray) {
        // TODO(iOS online-first): multipart upload to …/leftovers/analyze lands with
        // the capture/op-rail work (the leftover capture flow is not wired yet).
        throw NotImplementedError("Leftover photo analyze is not wired on iOS yet")
    }

    override suspend fun applyLeftovers(date: String, entryId: String): Entry {
        val entry = client.post("api/me/nutrition/$date/entries/$entryId/leftovers/apply").body<Entry>()
        refreshDay(date)
        return entry
    }

    override suspend fun discardLeftovers(date: String, entryId: String): Entry {
        val entry = client.post("api/me/nutrition/$date/entries/$entryId/leftovers/discard").body<Entry>()
        refreshDay(date)
        return entry
    }

    override suspend fun restoreLeftovers(date: String, entryId: String): Entry {
        val entry = client.post("api/me/nutrition/$date/entries/$entryId/leftovers/restore").body<Entry>()
        refreshDay(date)
        return entry
    }

    // ---- Composite meal editing -------------------------------------------

    override suspend fun updateComposite(
        date: String,
        entryId: String,
        title: String,
        portion: Double,
        quantities: List<Double>,
    ): Entry {
        // TODO(iOS online-first): the composite edit sheet (per-ingredient re-portion
        // + title) is a placeholder; wire this when the edit sheet is built.
        throw NotImplementedError("Composite meal editing is not wired on iOS yet")
    }

    override suspend fun removeIngredient(date: String, entryId: String, index: Int): NutritionDayRepository.RemovedIngredient {
        // TODO(iOS online-first): ingredient remove/undo lands with the edit sheet.
        throw NotImplementedError("Ingredient editing is not wired on iOS yet")
    }

    override suspend fun restoreIngredient(
        date: String,
        entryId: String,
        index: Int,
        ingredient: EntryIngredient,
    ): Entry {
        throw NotImplementedError("Ingredient editing is not wired on iOS yet")
    }

    // ---- Serving hint (lazy) ----------------------------------------------

    override suspend fun servingHint(date: String, entryId: String): String? =
        runCatching {
            client.get("api/me/nutrition/$date/entries/$entryId/serving-hint").body<ServingHintResponse>().hint
        }.getOrNull()

    // ---- Describe / relog / saved meal ------------------------------------

    override suspend fun describeMealAsync(date: String, description: String, meal: String) {
        client.post("api/me/nutrition/$date/describe-meal-async") {
            header("Idempotency-Key", mintKey())
            setBody(DescribeMealLogRequest(description = description, meal = meal, id = mintKey()))
        }
        refreshDay(date)
    }

    override suspend fun relog(date: String, source: Entry, meal: String): Entry {
        // Online-first re-log of a non-composite entry: snapshot the source's macros
        // into a fresh entry on the target meal. Composite re-log (which re-drives AI
        // image generation via the describe path) lands with the op rail.
        if (source.isComposite) {
            throw NotImplementedError("Composite re-log is not wired on iOS yet")
        }
        return addEntry(
            date,
            EntryRequest(
                meal = meal,
                foodId = source.foodId,
                foodName = source.foodName,
                servingLabel = source.servingLabel ?: "1 serving",
                servingGrams = source.servingGrams ?: 100.0,
                quantity = source.quantity,
                macros = source.macros,
                source = "RELOG",
            ),
        )
    }

    override suspend fun logDescribedMeal(
        date: String,
        mealId: String,
        meal: String,
        label: String,
        knownImageUrl: String?,
        knownImageStatus: String,
    ) {
        client.post("api/me/nutrition/$date/describe-meal") {
            header("Idempotency-Key", mintKey())
            setBody(DescribeMealLogRequest(mealId = mealId, meal = meal, id = mintKey()))
        }
        refreshDay(date)
    }

    // ---- Add-food search support ------------------------------------------

    override suspend fun recentMeals(meal: String?): List<Entry> =
        client.get("api/me/nutrition/recent-meals") {
            parameter("days", RECENT_DAYS)
            parameter("limit", RECENT_LIMIT)
            meal?.let { parameter("meal", it) }
        }.body()

    /** No local cache online-first — the networked [recentMeals] is the only source. */
    override suspend fun cachedRecentMeals(meal: String?): List<Entry> = emptyList()

    override suspend fun searchMeals(query: String): List<MealSearchResult> =
        if (query.isBlank()) emptyList()
        else client.get("api/me/nutrition/meals/search") { parameter("q", query) }.body()

    override suspend fun cachedSearchMeals(query: String): List<MealSearchResult> = emptyList()

    override suspend fun archiveMeal(mealId: String) {
        client.post("api/me/nutrition/meals/$mealId/archive")
    }

    // ---- helpers ----------------------------------------------------------

    /** A client-minted id / idempotency key. Online-first (no replay) so a random
     *  hex token is sufficient; the durable op rail will mint persisted ids later. */
    private fun mintKey(): String = "ios-" + Random.nextLong().toString(16).removePrefix("-")

    private companion object {
        const val RECENT_DAYS = 14
        const val RECENT_LIMIT = 20
    }
}

// ---------------------------------------------------------------------------
// Request/response bodies for the endpoints above (mirrors Android's
// core-domain request classes 1:1; kept here with the repo that sends them).
// ---------------------------------------------------------------------------

@Serializable
private data class AdjustStartRequest(val instruction: String, val saveAsMeal: Boolean = false)

@Serializable
private data class AdjustCommitRequest(val saveAsMeal: Boolean? = null)

@Serializable
private data class DescribeMealLogRequest(
    val mealId: String? = null,
    val description: String? = null,
    val meal: String? = null,
    /** Client-minted entry id for idempotent replay; null ⇒ server-generated. */
    val id: String? = null,
)

@Serializable
private data class ServingHintResponse(val hint: String? = null)
