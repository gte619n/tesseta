package com.gte619n.healthfitness.shared.domain.nutrition

import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 1A — REFERENCE EXTRACTION. The Android
 * `core-domain .../domain/nutrition/Nutrition.kt` models moved to `commonMain`,
 * with Moshi's implicit reflection codec replaced by explicit
 * `@Serializable` (kotlinx.serialization, D3). Behavior is unchanged: field
 * names and nullability match the backend wire contract 1:1, proven by the
 * `contracts/fixtures/` round-trip (D3 gate — the codec swap is verified, not
 * assumed). The remaining domain files follow this exact pattern.
 *
 * Pure computations (`forPortion`, `derivedCaloriesKcal`, `Meal.forHour`) are
 * carried verbatim — they are the XPLAT-004 (`compositeTotal`) / XPLAT-008
 * (`LB_PER_KG`) divergence surfaces the audit flagged, now single-sourced.
 */

/** The six tracked macros. Every field is nullable per the contract. */
@Serializable
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

@Serializable
data class ServingSize(val label: String, val grams: Double)

@Serializable
data class AlcoholInfo(
    val abvPercent: Double? = null,
    val servingVolumeMl: Double? = null,
    val alcoholGrams: Double? = null,
    val standardDrinks: Double? = null,
)

@Serializable
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

/** One logged food on a given day + meal. Macros are a frozen snapshot. */
@Serializable
data class Entry(
    val entryId: String,
    val meal: String,
    val foodId: String? = null,
    val foodName: String,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double,
    val macros: Macros,
    val source: String,
    val imageUrl: String? = null,
    val photoUrl: String? = null,
    val imageStatus: String = "NONE",
    val analysisStatus: String = "NONE",
    val ingredients: List<EntryIngredient>? = null,
    val syncState: String? = null,
    val date: String? = null,
) {
    val isComposite: Boolean get() = !ingredients.isNullOrEmpty()
    val isAnalyzing: Boolean get() = analysisStatus == "ANALYZING"
    val isImageEligible: Boolean get() = isComposite || foodId != null
    val isImageMissing: Boolean
        get() = isImageEligible && !isAnalyzing &&
            imageStatus != "READY" && imageStatus != "PENDING"
}

@Serializable
data class MealGroup(val meal: String, val subtotal: Macros, val entries: List<Entry> = emptyList())

/** GET api/me/nutrition/{date} response: the full day. */
@Serializable
data class NutritionDay(
    val date: String,
    val totals: Macros,
    val target: Macros? = null,
    val meals: List<MealGroup> = emptyList(),
)

/** The four meals, in display order. */
enum class Meal(val wire: String, val label: String) {
    BREAKFAST("BREAKFAST", "Breakfast"),
    LUNCH("LUNCH", "Lunch"),
    DINNER("DINNER", "Dinner"),
    SNACK("SNACK", "Snack"),
    ;

    companion object {
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
 * backend enforces on every write. Null when no macro is present at all.
 */
fun derivedCaloriesKcal(proteinGrams: Double?, carbsGrams: Double?, fatGrams: Double?): Double? =
    if (proteinGrams == null && carbsGrams == null && fatGrams == null) null
    else (proteinGrams ?: 0.0) * 4 + (carbsGrams ?: 0.0) * 4 + (fatGrams ?: 0.0) * 9

/**
 * Compute the macro snapshot for a portion:
 *   macros = macrosPer100g × (servingGrams × quantity) / 100
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
