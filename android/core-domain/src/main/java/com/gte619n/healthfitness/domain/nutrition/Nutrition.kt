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
    val servingLabel: String,
    val servingGrams: Double,
    val quantity: Double,
    val macros: Macros,
    val source: String,
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
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double? = null,
    val macros: Macros? = null,
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
}

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
