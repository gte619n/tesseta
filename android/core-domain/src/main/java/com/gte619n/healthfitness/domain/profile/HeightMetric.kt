package com.gte619n.healthfitness.domain.profile

import kotlin.math.roundToInt

// Display-time conversion helper. Backend stores height in centimeters; the
// US-facing UI shows feet/inches. Conversion math lives here so ViewModels
// stay free of unit arithmetic. Mirrors the helpers in
// web/components/profile/HeightForm.tsx.
object HeightMetric {
    const val CM_PER_INCH = 2.54
    const val INCHES_PER_FOOT = 12

    data class FtIn(val feet: Int, val inches: Int)

    // null in -> null out. Rounds to the nearest whole inch, then normalizes
    // so inches is always 0..11 (e.g. 72in -> 6'0", not 5'12").
    fun cmToFtIn(cm: Int?): FtIn? {
        if (cm == null) return null
        val totalInches = (cm / CM_PER_INCH).roundToInt()
        val feet = totalInches / INCHES_PER_FOOT
        val inches = totalInches % INCHES_PER_FOOT
        return FtIn(feet, inches)
    }

    // feet/inches -> centimeters, rounded to the nearest whole cm.
    fun ftInToCm(feet: Int, inches: Int): Int {
        val totalInches = feet * INCHES_PER_FOOT + inches
        return (totalInches * CM_PER_INCH).roundToInt()
    }
}
