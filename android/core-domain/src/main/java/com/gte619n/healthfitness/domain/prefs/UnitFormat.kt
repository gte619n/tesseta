package com.gte619n.healthfitness.domain.prefs

import kotlin.math.roundToInt

/**
 * Pref-aware unit formatting. The app stores weight in pounds and height in
 * centimeters internally; these helpers convert to the user's chosen display
 * unit at the edge (UI), so storage/models stay unchanged.
 */
object UnitFormat {
    const val LB_PER_KG = 2.2046226218
    const val CM_PER_INCH = 2.54

    // ---- Weight (stored as pounds) ----

    fun weightLabel(unit: WeightUnit): String = when (unit) {
        WeightUnit.POUNDS -> "lb"
        WeightUnit.KILOGRAMS -> "kg"
    }

    /** Numeric value of a pound amount in the chosen unit (no label). */
    fun weightValue(valueLb: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.POUNDS -> valueLb
        WeightUnit.KILOGRAMS -> valueLb / LB_PER_KG
    }

    /** e.g. "188.4" — value only, formatted to [decimals]. */
    fun weightValueString(valueLb: Double, unit: WeightUnit, decimals: Int = 1): String =
        fmt(weightValue(valueLb, unit), decimals)

    /** e.g. "188.4 lb" / "85.5 kg". */
    fun weight(valueLb: Double, unit: WeightUnit, decimals: Int = 1): String =
        "${weightValueString(valueLb, unit, decimals)} ${weightLabel(unit)}"

    // ---- Temperature (stored/sourced as Celsius) ----

    fun temperatureLabel(unit: TemperatureUnit): String = when (unit) {
        TemperatureUnit.FAHRENHEIT -> "°F"
        TemperatureUnit.CELSIUS -> "°C"
    }

    fun temperatureValue(celsius: Double, unit: TemperatureUnit): Double = when (unit) {
        TemperatureUnit.CELSIUS -> celsius
        TemperatureUnit.FAHRENHEIT -> celsius * 9.0 / 5.0 + 32.0
    }

    fun temperature(celsius: Double, unit: TemperatureUnit, decimals: Int = 1): String =
        "${fmt(temperatureValue(celsius, unit), decimals)} ${temperatureLabel(unit)}"

    private fun fmt(v: Double, decimals: Int): String =
        if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
}
