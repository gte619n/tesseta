package com.gte619n.healthfitness.domain.nutrition

// Nutrition domain models (IMPL-13). These mirror the backend JSON contract
// exactly (see docs/specs/IMPL-13-nutrition-tracking.md "Data model" and
// "REST endpoints") and double as the Moshi wire types. All macro fields are
// nullable Doubles — a food/target may not carry every nutrient. Dates are
// kept as ISO-8601 strings (yyyy-MM-dd) and parsed in the UI layer, mirroring
// the Goals module.

/** The six tracked macros. Every field is nullable per the contract. */
data class Macros(
    val caloriesKcal: Double? = null,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val fiberGrams: Double? = null,
    val sugarGrams: Double? = null,
) {
    companion object {
        val EMPTY = Macros()
    }
}

data class ServingSize(
    val label: String,
    val grams: Double,
)

/**
 * A saved-meal hit in the add-food search (GET api/me/nutrition/meals/search).
 * Logged by [mealId] via the describe-meal path, which reuses the meal's
 * ingredient breakdown + plated photo. [macros]/[totalGrams] are one serving.
 */
data class MealSearchResult(
    val mealId: String,
    val name: String,
    val macros: Macros,
    val totalGrams: Double? = null,
    val imageUrl: String? = null,
    val imageStatus: String = "NONE",
    val mine: Boolean = false,
)

/** A globally shared, reusable catalog food. */
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
)

/** One logged food on a given day + meal. Macros are a frozen snapshot. */
data class Entry(
    val entryId: String,
    val meal: String,
    val foodId: String? = null,
    val foodName: String,
    // Null on an ANALYZING placeholder (a freshly captured photo not yet
    // itemized server-side); filled in once analysis finalizes the entry.
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double,
    val macros: Macros,
    val source: String,
    // Joined in from the entry's catalog food (null/"NONE" for manual entries).
    // For a composite (photo-logged) meal this is the finished-meal image.
    val imageUrl: String? = null,
    val imageStatus: String = "NONE",
    // Background photo-analysis lifecycle: NONE for ordinary entries, ANALYZING
    // while a freshly captured photo is still being itemized server-side, then
    // READY (or FAILED). An ANALYZING entry is a placeholder to be polled.
    val analysisStatus: String = "NONE",
    // Present for a composite meal: its components, each with a raw-ingredient
    // image. Null/empty for a plain single-food entry.
    val ingredients: List<EntryIngredient>? = null,
    /**
     * IMPL-AND-20 (#40) — the mirror row's per-row sync state
     * (`SYNCED | PENDING | FAILED`) for the D11 SyncBadge. Null for a live read.
     * Defaulted so existing constructions are unaffected.
     */
    val syncState: String? = null,
    /**
     * The day this entry was logged on (ISO yyyy-MM-dd). Only populated by
     * reads that span days — the recent-meals list, where it identifies the
     * source entry for a one-tap re-log. Null on day-view reads.
     */
    val date: String? = null,
    /**
     * Transient, client-only path to the user's just-captured photo (a device
     * cache file). Set while a photographed meal is uploading/analyzing/generating
     * its image, so the row shows the real photo (with a loader over it) instead of
     * a spinner — until the server's generated [imageUrl] lands READY. Never
     * persisted to the mirror as a real value or sent to the server; injected at
     * day-assembly time from the capture-preview store and dropped once READY.
     */
    val localImagePath: String? = null,
) {
    val isComposite: Boolean get() = !ingredients.isNullOrEmpty()

    /** True while the server is still analyzing the captured photo. */
    val isAnalyzing: Boolean get() = analysisStatus == "ANALYZING"

    /**
     * True when this entry is supposed to have a generated picture: a composite
     * meal (its own finished-meal image) or a catalog-backed food (its studio
     * image, joined server-side). A pure "quick add" (no [foodId], no
     * ingredients) legitimately has no image, so it never shows a retry — just
     * the neutral placeholder.
     */
    val isImageEligible: Boolean get() = isComposite || foodId != null

    /**
     * True when an image is expected but absent and not currently being produced
     * — i.e. NONE (never generated) or FAILED, and not mid-analysis or PENDING.
     * Drives the in-card retry affordance and keeps the day re-fetching/polling so
     * a server-side self-heal converges the picture without user action.
     */
    val isImageMissing: Boolean
        get() = isImageEligible && !isAnalyzing &&
            imageStatus != "READY" && imageStatus != "PENDING"
}

/** One ingredient of a composite meal, with its generated raw-ingredient image. */
data class EntryIngredient(
    val name: String,
    val foodId: String? = null,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double? = null,
    val macros: Macros,
    val macrosPer100g: Macros? = null,
    val imageUrl: String? = null,
    val imageStatus: String = "NONE",
)

/** One meal group within a day: its entries + computed subtotal. */
data class MealGroup(
    val meal: String,
    val subtotal: Macros,
    val entries: List<Entry> = emptyList(),
)

/** GET api/me/nutrition/{date} response: the full day. */
data class NutritionDay(
    val date: String,
    val totals: Macros,
    val target: Macros? = null,
    val meals: List<MealGroup> = emptyList(),
)

/** One row from GET api/me/nutrition?from=&to= (the daily rollup range). */
data class DailyRollup(
    val date: String,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val fiberGrams: Double? = null,
    val sugarGrams: Double? = null,
    val caloriesKcal: Double? = null,
)

// ---- Request bodies -------------------------------------------------------

/** Body for POST api/me/nutrition/{date}/entries. */
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

/** Body for PATCH api/me/nutrition/{date}/entries/{entryId} (all optional). */
data class EntryPatchRequest(
    val meal: String? = null,
    val foodName: String? = null,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double? = null,
    val macros: Macros? = null,
)

/** Body for POST api/me/nutrition/{date}/composite-meal. */
data class CompositeMealRequest(
    val meal: String,
    val mealName: String,
    val ingredients: List<CompositeIngredientRequest>,
    val referencePhotoRef: String? = null,
)

data class CompositeIngredientRequest(
    val name: String,
    val servingGrams: Double? = null,
    val servingLabel: String? = null,
    val quantity: Double? = null,
    val macrosPer100g: Macros? = null,
    val macros: Macros? = null,
)

/** Body for PATCH api/me/nutrition/{date}/entries/{entryId}/ingredients/{index}. */
data class UpdateIngredientRequest(
    val servingGrams: Double? = null,
    val servingLabel: String? = null,
    val quantity: Double? = null,
)

/** Body for POST api/foods (create a catalog food). */
data class FoodCreateRequest(
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val category: String? = null,
    val macrosPer100g: Macros,
    val servingSizes: List<ServingSize> = emptyList(),
    val defaultServingIndex: Int = 0,
    /** Client-minted food id for idempotent replay from a durable op worker; null ⇒ server-generated. */
    val id: String? = null,
)

// ---- Describe a meal ------------------------------------------------------

/** Body for POST api/nutrition/describe. */
data class DescribeMealRequest(
    val description: String,
)

/** One component of a described meal, with its frozen per-100g baseline. */
data class DescribedIngredient(
    val name: String,
    val servingGrams: Double? = null,
    val servingLabel: String? = null,
    val quantity: Double? = null,
    val macros: Macros,
    val macrosPer100g: Macros? = null,
)

/**
 * POST api/nutrition/describe response: a resolved meal — a previously-saved
 * match (`matched`) or a freshly created one — with macros, ingredient
 * breakdown and studio-photo status. Logged onto a day by [mealId].
 */
data class DescribedMeal(
    val mealId: String,
    val matched: Boolean,
    val name: String,
    val totalGrams: Double? = null,
    val macros: Macros,
    val imageUrl: String? = null,
    val imageStatus: String = "NONE",
    val ingredients: List<DescribedIngredient> = emptyList(),
)

/** Body for POST api/me/nutrition/{date}/describe-meal (mealId OR description). */
data class DescribeMealLogRequest(
    val mealId: String? = null,
    val description: String? = null,
    val meal: String? = null,
    /** Client-minted entry id for idempotent replay from a durable op worker; null ⇒ server-generated. */
    val id: String? = null,
)

// ---- Adjust with AI -------------------------------------------------------

/**
 * One component of an AI meal correction — a proposed item on preview, or an
 * accepted item echoed back on apply. Mirrors the backend AdjustItemDto.
 */
data class AdjustItem(
    val name: String,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val macrosPer100g: Macros? = null,
    val macros: Macros? = null,
)

/** Body for POST api/me/nutrition/{date}/entries/{entryId}/adjust/preview. */
data class AdjustPreviewRequest(
    val instruction: String,
)

/**
 * POST …/adjust/preview response: the revised meal as a non-persisted proposal,
 * plus the before/after day-total macros so the client can render the diff.
 */
data class AdjustPreviewResponse(
    val mealName: String,
    val packagedProduct: Boolean = false,
    val items: List<AdjustItem> = emptyList(),
    val newTotals: Macros = Macros.EMPTY,
    val oldTotals: Macros = Macros.EMPTY,
)

/**
 * Body for POST …/adjust/apply: the accepted proposal echoed back, plus whether
 * to also save the corrected meal to the shared catalog for reuse.
 */
data class AdjustApplyRequest(
    val mealName: String,
    val packagedProduct: Boolean = false,
    val items: List<AdjustItem> = emptyList(),
    val saveAsMeal: Boolean = false,
)

// ---- Capture (multipart) proposals ---------------------------------------

/** One itemized component of a meal photo (POST capture/meal). */
data class MealCaptureItem(
    val name: String,
    val estimatedPortionGrams: Double,
    val suggestedServingLabel: String,
    val macrosPer100g: Macros,
    val macrosForPortion: Macros,
    val confidence: Double,
    val matchedFoodId: String? = null,
)

/** POST api/nutrition/capture/meal response. */
data class MealCaptureResponse(
    val photoRef: String,
    val items: List<MealCaptureItem> = emptyList(),
)

/** The packaged-food draft proposed by a label photo. */
data class LabelCaptureFood(
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val macrosPer100g: Macros,
    val servingSizes: List<ServingSize> = emptyList(),
    val defaultServingIndex: Int = 0,
    val source: String,
)

/** POST api/nutrition/capture/label response. */
data class LabelCaptureResponse(
    val photoRef: String,
    val food: LabelCaptureFood,
)

// ---- Helpers --------------------------------------------------------------

/** The four meals, in display order. */
enum class Meal(val wire: String, val label: String) {
    BREAKFAST("BREAKFAST", "Breakfast"),
    LUNCH("LUNCH", "Lunch"),
    DINNER("DINNER", "Dinner"),
    SNACK("SNACK", "Snack"),
    ;

    companion object {
        /**
         * Infer the meal from the local hour of day, so capture flows don't have
         * to prompt the user. Windows: breakfast 04:00–10:59, lunch 11:00–15:59,
         * dinner 16:00–21:59, snack otherwise (late night / early morning).
         */
        fun forHour(hour: Int): Meal = when (hour) {
            in 4..10 -> BREAKFAST
            in 11..15 -> LUNCH
            in 16..21 -> DINNER
            else -> SNACK
        }
    }
}

/**
 * Calories derived from macros under Atwater 4/4/9 — the same invariant the
 * backend enforces on every write, so the UI can show the value live as the
 * user types. Null when no macro is present at all (a calories-only entry
 * stays manually enterable).
 */
fun derivedCaloriesKcal(
    proteinGrams: Double?,
    carbsGrams: Double?,
    fatGrams: Double?,
): Double? =
    if (proteinGrams == null && carbsGrams == null && fatGrams == null) null
    else (proteinGrams ?: 0.0) * 4 + (carbsGrams ?: 0.0) * 4 + (fatGrams ?: 0.0) * 9

/**
 * Compute the macro snapshot for a portion:
 *   macros = macrosPer100g × (servingGrams × quantity) / 100
 * Null source fields stay null (the nutrient is simply unknown).
 */
fun Macros.forPortion(servingGrams: Double, quantity: Double): Macros {
    val factor = (servingGrams * quantity) / 100.0
    fun scale(v: Double?): Double? = v?.let { it * factor }
    return Macros(
        caloriesKcal = scale(caloriesKcal),
        proteinGrams = scale(proteinGrams),
        carbsGrams = scale(carbsGrams),
        fatGrams = scale(fatGrams),
        fiberGrams = scale(fiberGrams),
        sugarGrams = scale(sugarGrams),
    )
}
