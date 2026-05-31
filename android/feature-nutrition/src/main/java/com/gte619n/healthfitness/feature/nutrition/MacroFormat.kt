package com.gte619n.healthfitness.feature.nutrition

import com.gte619n.healthfitness.domain.nutrition.Macros
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Small presentation helpers for the nutrition feature. Pure functions so they
// are unit-testable without Compose (see MacroFormatTest).

internal val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/** Round a macro value for display; null → "—". Grams get no unit here. */
fun formatGrams(value: Double?): String =
    if (value == null) "—" else "${value.roundToInt()} g"

fun formatKcal(value: Double?): String =
    if (value == null) "—" else "${value.roundToInt()} kcal"

/** Friendly day label, e.g. "Today", "Yesterday", or "Mon, May 30". */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
    }

/**
 * Progress fraction [0,1] of consumed vs target for a single nutrient.
 * Returns null when there is no (positive) target to measure against.
 */
fun progressFraction(consumed: Double?, target: Double?): Float? {
    if (target == null || target <= 0.0) return null
    val c = consumed ?: 0.0
    return (c / target).toFloat().coerceIn(0f, 1f)
}

/** Remaining amount toward a target (never negative); null target → null. */
fun remaining(consumed: Double?, target: Double?): Double? {
    if (target == null) return null
    val c = consumed ?: 0.0
    return (target - c).coerceAtLeast(0.0)
}

/** The six nutrient accessors, paired with a short label and unit, in order. */
enum class NutrientRow(val label: String, val grams: Boolean) {
    CALORIES("Calories", grams = false),
    PROTEIN("Protein", grams = true),
    CARBS("Carbs", grams = true),
    FAT("Fat", grams = true),
    FIBER("Fiber", grams = true),
    SUGAR("Sugar", grams = true),
    ;

    fun valueOf(m: Macros?): Double? = when (this) {
        CALORIES -> m?.caloriesKcal
        PROTEIN -> m?.proteinGrams
        CARBS -> m?.carbsGrams
        FAT -> m?.fatGrams
        FIBER -> m?.fiberGrams
        SUGAR -> m?.sugarGrams
    }

    fun format(value: Double?): String =
        if (grams) formatGrams(value) else formatKcal(value)
}
