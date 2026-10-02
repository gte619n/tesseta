package com.gte619n.healthfitness.shared.presentation.settings

import com.gte619n.healthfitness.shared.domain.nutrition.AlcoholInfo
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.ServingSize
import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — the drink-management domain types the Settings ›
 * Drinks surface needs but that the shared `domain/nutrition/Nutrition.kt` slice
 * hasn't extracted yet ([Food] with its drink block, and the analyze [DrinkProposal]).
 * Ported field-for-field from android/core-domain/.../nutrition/Nutrition.kt so the
 * shared [DrinkSettingsViewModel] matches Android 1:1. When the full `Food` lands
 * in the shared nutrition domain these move there; kept here (in scope) for now.
 */

/** A globally shared, reusable catalog food (the subset the drink screen renders). */
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
    /** Non-null only for a `category="drink"` catalog food; carries the alcohol facts. */
    val alcohol: AlcoholInfo? = null,
    /**
     * Per-serving macros for a `category="drink"` food: `caloriesKcal` includes the
     * alcohol calories; protein/carbs/fat/fiber/sugar are the per-serving MIXER
     * contribution. Used to show per-serving calories and prefill the edit form.
     */
    val servingMacros: Macros? = null,
)

/**
 * The AI proposal returned by `POST /api/me/drinks/analyze` for a free-text drink
 * name. `servingMacros` is the proposed per-serving macros; `alcohol` the derived
 * alcohol facts, both shown read-only in the review form. The backend returns 422
 * when AI is unavailable — the UI then falls back to fully-manual entry.
 */
@Serializable
data class DrinkProposal(
    val name: String,
    val abvPercent: Double? = null,
    val servingVolumeMl: Double? = null,
    val servingMacros: Macros? = null,
    val alcohol: AlcoholInfo? = null,
)

/** Format a Double without a trailing ".0" so form fields read cleanly. */
internal fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Return a copy of this list with the elements at [a] and [b] swapped. Pure +
 * bounds-safe (returns the list unchanged if either index is out of range) so the
 * move-up/move-down reorder is unit-testable without the ViewModel.
 */
internal fun <T> List<T>.swapped(a: Int, b: Int): List<T> {
    if (a == b || a !in indices || b !in indices) return this
    return toMutableList().also { it[a] = this[b]; it[b] = this[a] }
}

/** Platform-agnostic CSRF `state` token for the Withings OAuth round-trip. */
internal fun randomOAuthState(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(32) { append(chars[Random.nextInt(chars.length)]) } }
}
