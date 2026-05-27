package com.gte619n.healthfitness.domain.profile

import kotlin.math.roundToInt

/**
 * Display-time conversion between the backend's `cm` storage and the
 * US-imperial ft/in pair shown in the profile editor. Lives in
 * `core-domain` so the ViewModel stays free of conversion math; mirrors
 * the helpers used on the web in `web/components/profile/HeightForm.tsx`.
 *
 * No unit-system preference yet — the cross-cutting ADR for lb/kg + in/cm
 * is deferred per IMPL-AND-02's "out of scope" list. When that lands, the
 * default branch in [cmToFtIn] becomes the conditional one.
 */
object HeightMetric {
    const val CM_PER_INCH: Double = 2.54
    const val INCHES_PER_FOOT: Int = 12

    data class FtIn(val feet: Int, val inches: Int)

    /** Returns null when the input is null so callers can render a placeholder. */
    fun cmToFtIn(cm: Int?): FtIn? {
        if (cm == null) return null
        val totalInches = (cm / CM_PER_INCH).roundToInt()
        val feet = totalInches / INCHES_PER_FOOT
        val inches = totalInches % INCHES_PER_FOOT
        return FtIn(feet = feet, inches = inches)
    }

    fun ftInToCm(feet: Int, inches: Int): Int {
        val totalInches = feet * INCHES_PER_FOOT + inches
        return (totalInches * CM_PER_INCH).roundToInt()
    }
}
