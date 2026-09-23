package com.gte619n.healthfitness.shared.presentation.nutrition

import com.gte619n.healthfitness.shared.data.AdjustStatus
import com.gte619n.healthfitness.shared.data.LeftoverStatus
import com.gte619n.healthfitness.shared.data.MealAdjustment
import com.gte619n.healthfitness.shared.data.Leftover
import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import kotlin.math.roundToLong

/**
 * IMPL-IOS-01 Phase 3 Wave C — pure nutrition presentation helpers, ported
 * verbatim from Android `feature-nutrition/MacroFormat.kt` (+ the `Entry` state
 * extensions that live on the Android domain but aren't yet in the reference
 * `Nutrition.kt`). Single-sourced here so iOS and Android format macros, compute
 * progress, and classify entry state IDENTICALLY (the XPLAT divergence surface).
 *
 * These are the functions `commonTest` exercises and the SwiftUI views call
 * through the bridged VM output.
 */

/** Round to a whole number with thousands separators; mirrors Android
 *  `formatWholeNumber` (grouping on, 0 decimals). */
fun formatWholeNumber(value: Double): String {
    val rounded = value.roundToLong()
    val negative = rounded < 0
    val digits = kotlin.math.abs(rounded).toString()
    val grouped = buildString {
        val n = digits.length
        for (i in digits.indices) {
            if (i > 0 && (n - i) % 3 == 0) append(',')
            append(digits[i])
        }
    }
    return if (negative) "-$grouped" else grouped
}

/** Round a macro value for display; null → "—". */
fun formatGrams(value: Double?): String =
    if (value == null) "—" else "${formatWholeNumber(value)} g"

fun formatKcal(value: Double?): String =
    if (value == null) "—" else "${formatWholeNumber(value)} kcal"

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

/** True when consumed has exceeded a positive target (bar turns "alert"). */
fun isOver(consumed: Double?, target: Double?): Boolean {
    if (target == null || target <= 0.0) return false
    return (consumed ?: 0.0) > target
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

// ---------------------------------------------------------------------------
// Entry state extensions — mirror the Android `Entry` computed properties for
// adjust/leftover state. (The reference Nutrition.kt carries the image/analysis
// ones; these depend on the adjust/leftover types declared in
// NutritionRepositories.kt, so they live here.)
// ---------------------------------------------------------------------------

/**
 * NOTE: the reference `Entry` (Nutrition.kt) does not yet carry
 * `leftover`/`adjustment` fields, and we must not edit it. The Wave-C VMs
 * therefore read adjust/leftover state through the [EntryState] carrier the
 * repository attaches alongside each entry (below), keeping this file pure and
 * the reference untouched. When Nutrition.kt grows the fields, `EntryState`
 * collapses into direct property reads.
 */

/**
 * The adjust/leftover state the repository resolves for an entry (kept out of the
 * reference `Entry`). The VM/UI treats `(entry, state)` as a pair. Field names +
 * derived predicates match the Android `Entry` computed props 1:1.
 */
data class EntryState(
    val entryId: String,
    val leftover: Leftover? = null,
    val adjustment: MealAdjustment? = null,
) {
    val isAdjusting: Boolean get() = adjustment?.status == AdjustStatus.ADJUSTING
    val hasAdjustReview: Boolean
        get() = adjustment?.status == AdjustStatus.PENDING_REVIEW && adjustment.proposal != null
    val isAnalyzingLeftovers: Boolean get() = leftover?.status == LeftoverStatus.ANALYZING
    val hasLeftoverReview: Boolean
        get() = leftover?.status == LeftoverStatus.PENDING_REVIEW && leftover.proposal != null
    val hasAppliedLeftover: Boolean get() = leftover?.status == LeftoverStatus.APPLIED
}

/** True when an entry is eligible for the remove-leftovers flow (composite,
 *  not analyzing, not a pending synthetic row). Mirrors Android
 *  `Entry.isLeftoverEligible`. */
fun Entry.isLeftoverEligible(state: EntryState?): Boolean =
    isComposite && !isAnalyzing && (state?.isAnalyzingLeftovers != true) &&
        !entryId.startsWith(PENDING_CAPTURE_PREFIX)
