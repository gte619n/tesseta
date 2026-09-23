package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.EntryIngredient
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 Wave C — Nutrition repository contracts + the supporting
 * domain types the shared nutrition ViewModels observe/drive.
 *
 * The base [NutritionRepository] (observeDay/refresh) already lives in
 * `Repositories.kt` (a reference file we must NOT edit); this file adds the
 * FULL surface the Wave-C flows need, split into cohesive interfaces so a
 * feature vertical (and its `commonTest` fakes) can depend on only what it uses:
 *
 *   - [NutritionDayRepository]  — the Today page: reactive day + imperative
 *     day/cachedDay/refresh + all entry mutations, composite editing, adjust,
 *     leftover, image-heal, describe/relog/saved-meal, serving hint.
 *   - [FoodRepository]          — catalog food search / barcode lookup (capture).
 *   - [NutritionCaptureRepository] — the multipart AI analyze endpoints (label).
 *   - [NutritionOpQueue]        — the durable op-rail the capture screen enqueues
 *     onto; its in-flight ops project synthetic "logging…" rows onto the day.
 *
 * These are the KMP ports of the Android `data.nutrition.*Repository` contracts;
 * the concrete Room-KMP + Ktor implementations are Phase 1C. Field names + method
 * shapes match Android 1:1 so nutrition math and wire contracts stay
 * single-sourced (the XPLAT divergence surface the audit flagged).
 *
 * Supporting domain types (Food / MealAdjustment / Leftover / capture drafts /
 * requests / op entities) that the reference `domain/nutrition/Nutrition.kt` does
 * not yet carry are declared HERE (this file is Wave C's to own) rather than
 * editing the reference; they mirror the Android `core-domain` definitions
 * verbatim. When the domain file grows these, they move over unchanged.
 */

// ---------------------------------------------------------------------------
// Supporting domain types (mirrored from Android core-domain, kept here so the
// Wave-C VMs compile without editing the reference Nutrition.kt).
// ---------------------------------------------------------------------------

@Serializable
data class ServingSize(val label: String, val grams: Double)

/** A global catalog food (barcode/label/AI/manual). */
@Serializable
data class Food(
    val foodId: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val category: String? = null,
    val macrosPer100g: Macros,
    val servingSizes: List<ServingSize> = emptyList(),
    val defaultServingIndex: Int = 0,
    val source: String,
    val status: String,
    val confirmationCount: Int = 0,
    val imageUrl: String? = null,
    val imageStatus: String,
    val servingMacros: Macros? = null,
)

/** POST …/entries body — macros are snapshotted at log time. */
@Serializable
data class EntryRequest(
    val meal: String,
    val foodId: String? = null,
    val foodName: String,
    val servingLabel: String,
    val servingGrams: Double,
    val quantity: Double,
    val macros: Macros,
    val source: String,
)

/** PATCH …/entries/{id} — every field optional (only the changed ones sent). */
@Serializable
data class EntryPatchRequest(
    val meal: String? = null,
    val foodName: String? = null,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double? = null,
    val macros: Macros? = null,
)

// ---- Adjust with AI (async) ----------------------------------------------

enum class AdjustStatus { ADJUSTING, PENDING_REVIEW, REJECTED }

@Serializable
data class AdjustItem(
    val name: String,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val macrosPer100g: Macros? = null,
    val macros: Macros? = null,
)

/** The stored old→new proposal shown in the adjust review sheet. */
@Serializable
data class AdjustProposal(
    val mealName: String,
    val packagedProduct: Boolean = false,
    val items: List<AdjustItem> = emptyList(),
    val newTotals: Macros = Macros.EMPTY,
    val oldTotals: Macros = Macros.EMPTY,
)

@Serializable
data class MealAdjustment(
    val status: AdjustStatus? = null,
    val instruction: String? = null,
    val saveAsMeal: Boolean = false,
    val proposal: AdjustProposal? = null,
)

// ---- Remove leftovers -----------------------------------------------------

enum class LeftoverStatus { ANALYZING, PENDING_REVIEW, REJECTED, APPLIED }

@Serializable
data class LeftoverProposalItem(
    val name: String,
    val servedGrams: Double? = null,
    val consumedGrams: Double? = null,
    val remainingGrams: Double? = null,
    val matched: Boolean = true,
    val consumedMacros: Macros? = null,
)

@Serializable
data class LeftoverProposal(
    val items: List<LeftoverProposalItem> = emptyList(),
    val servedTotals: Macros? = null,
    val consumedTotals: Macros? = null,
    val overallConfidence: Double = 0.0,
    val warning: Boolean = false,
    val warningNote: String? = null,
)

@Serializable
data class Leftover(
    val status: LeftoverStatus? = null,
    val servedMacros: Macros? = null,
    val proposal: LeftoverProposal? = null,
)

// ---- Capture drafts -------------------------------------------------------

/** One AI-itemized food from a meal photo (editable before confirm). */
@Serializable
data class MealCaptureItem(
    val name: String,
    val estimatedPortionGrams: Double,
    val suggestedServingLabel: String,
    val macrosPer100g: Macros,
    val macrosForPortion: Macros,
    val confidence: Double,
    val matchedFoodId: String? = null,
)

/** A packaged-food draft parsed from a nutrition-label photo (OCR). */
@Serializable
data class LabelCaptureFood(
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val macrosPer100g: Macros,
    val servingSizes: List<ServingSize> = emptyList(),
    val defaultServingIndex: Int = 0,
    val source: String,
)

/** A saved-meal hit in the add-food search (logged with no AI rework). */
@Serializable
data class MealSearchResult(
    val mealId: String,
    val name: String,
    val macros: Macros,
    val totalGrams: Double? = null,
    val imageUrl: String? = null,
    val imageStatus: String = "NONE",
    val mine: Boolean = false,
)

// ---------------------------------------------------------------------------
// Durable op-rail (mirrors Android NutritionOp*; the capture screen enqueues,
// the Today page projects synthetic rows off the in-flight ops).
// ---------------------------------------------------------------------------

/** The kinds of durable nutrition op (survives process death). */
enum class NutritionOpType {
    CAPTURE_PHOTO,
    DESCRIBE_ASYNC,
    LOG_SAVED_MEAL,
    CONFIRM_MEAL_ITEMS,
    CONFIRM_LABEL,
    REMOVE_LEFTOVERS,
    ADJUST_MEAL,
}

/**
 * A queued durable nutrition op. Mirrors Android's Room `NutritionOpEntity` — the
 * capture screen enqueues one, a worker replays it (idempotent, client-minted
 * ids), and the Today page renders a synthetic row per in-flight op until the real
 * entry lands. `targetEntryId` is carried inline (rather than parsed out of
 * `payloadJson`) so the pure projection stays trivially testable in commonTest.
 */
@Serializable
data class NutritionOp(
    val id: String,
    val type: NutritionOpType,
    val date: String,
    val mealWire: String,
    val label: String,
    /** For REMOVE_LEFTOVERS / ADJUST_MEAL: the existing entry the op decorates. */
    val targetEntryId: String? = null,
    /** Local JPEG path for a CAPTURE_PHOTO op (shown as the synthetic thumbnail). */
    val localImagePath: String? = null,
)

// ---------------------------------------------------------------------------
// Repository contracts.
// ---------------------------------------------------------------------------

/**
 * The Today-page repository: the reactive day mirror + the full imperative
 * surface (entry mutations, composite editing, adjust/leftover flows, image
 * self-heal, describe/relog/saved-meal, serving hint). Port of the Android
 * `data.nutrition.NutritionRepository`.
 */
interface NutritionDayRepository {
    // Reactive + imperative reads
    fun observeDay(date: String): Flow<NutritionDay>
    suspend fun day(date: String): NutritionDay
    suspend fun cachedDay(date: String): NutritionDay?
    suspend fun refreshDay(date: String)

    // Target
    suspend fun target(): Macros?
    suspend fun setTarget(target: Macros): Macros

    // Entry mutations (offline-first local writes)
    suspend fun addEntry(date: String, body: EntryRequest): Entry
    suspend fun patchEntry(date: String, entryId: String, body: EntryPatchRequest): Entry
    suspend fun deleteEntry(date: String, entryId: String)

    // Composite meal editing
    data class RemovedIngredient(val entry: Entry, val ingredient: EntryIngredient, val index: Int)
    suspend fun updateComposite(
        date: String,
        entryId: String,
        title: String,
        portion: Double,
        quantities: List<Double>,
    ): Entry
    suspend fun removeIngredient(date: String, entryId: String, index: Int): RemovedIngredient
    suspend fun restoreIngredient(
        date: String,
        entryId: String,
        index: Int,
        ingredient: EntryIngredient,
    ): Entry

    // Adjust with AI (async submit / commit-discard on the stored proposal)
    suspend fun submitAdjust(date: String, entryId: String, instruction: String, saveAsMeal: Boolean)
    suspend fun commitAdjust(date: String, entryId: String, saveAsMeal: Boolean?): Entry
    suspend fun discardAdjust(date: String, entryId: String): Entry

    // Remove leftovers (analyze photo → review proposal → apply/discard/restore)
    suspend fun analyzeLeftovers(date: String, entryId: String, jpeg: ByteArray)
    suspend fun applyLeftovers(date: String, entryId: String): Entry
    suspend fun discardLeftovers(date: String, entryId: String): Entry
    suspend fun restoreLeftovers(date: String, entryId: String): Entry

    // Image self-heal
    suspend fun regenerateEntryImage(date: String, entryId: String): Entry
    suspend fun reanalyzeEntry(date: String, entryId: String): Entry

    // Serving hint (lazy)
    suspend fun servingHint(date: String, entryId: String): String?

    // Describe / relog / saved meal
    suspend fun describeMealAsync(date: String, description: String, meal: String)
    suspend fun relog(date: String, source: Entry, meal: String): Entry
    suspend fun logDescribedMeal(
        date: String,
        mealId: String,
        meal: String,
        label: String,
        knownImageUrl: String?,
        knownImageStatus: String,
    )

    // Add-food search support
    suspend fun recentMeals(meal: String?): List<Entry>
    suspend fun cachedRecentMeals(meal: String?): List<Entry>
    suspend fun searchMeals(query: String): List<MealSearchResult>
    suspend fun cachedSearchMeals(query: String): List<MealSearchResult>
    suspend fun archiveMeal(mealId: String)
}

/** Catalog food search + barcode lookup (the capture + add-food flows). */
interface FoodRepository {
    suspend fun search(query: String): List<Food>
    suspend fun localSearch(query: String): List<Food>
    suspend fun barcodeLookup(code: String): Food?
    suspend fun warmFromIds(foodIds: Collection<String>)
    suspend fun deleteFood(foodId: String)
}

/** The multipart AI-analyze endpoints (label OCR draft). Meal-photo + leftover
 *  uploads go through the durable [NutritionOpQueue] instead. */
interface NutritionCaptureRepository {
    suspend fun analyzeLabel(jpeg: ByteArray, barcode: String?): LabelCaptureFood
}

/** The durable op-rail the capture screen enqueues onto; the Today page observes
 *  the in-flight ops to project synthetic rows. Port of Android's
 *  NutritionOpEnqueuer + NutritionOpStore.observeAll(). */
interface NutritionOpQueue {
    fun observeAll(): Flow<List<NutritionOp>>
    suspend fun enqueueCapturePhoto(date: String, mealWire: String, jpeg: ByteArray): String
    suspend fun enqueueConfirmMealItems(date: String, mealWire: String, items: List<MealCaptureItem>): String
    suspend fun enqueueConfirmLabel(
        date: String,
        mealWire: String,
        draft: LabelCaptureFood,
        servingIndex: Int,
        quantity: Double,
    ): String
}
