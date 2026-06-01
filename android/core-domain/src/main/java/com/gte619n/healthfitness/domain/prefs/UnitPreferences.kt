package com.gte619n.healthfitness.domain.prefs

/**
 * User-selected display units. Stored on-device (DataStore) only — the server
 * is unit-agnostic (height is persisted in cm; weight values cross the wire as
 * today). Defaults are US/imperial to match the app's prior hardcoded behavior.
 */
data class UnitPreferences(
    val height: HeightUnit = HeightUnit.FEET_INCHES,
    val weight: WeightUnit = WeightUnit.POUNDS,
    val temperature: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
)

enum class HeightUnit { FEET_INCHES, CENTIMETERS }

enum class WeightUnit { POUNDS, KILOGRAMS }

enum class TemperatureUnit { FAHRENHEIT, CELSIUS }
