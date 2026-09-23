package com.gte619n.healthfitness.shared.domain.prefs

import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — extracted from
 * android/core-domain/.../domain/prefs/{UnitPreferences,CoachAudioSettings}.kt.
 *
 * On-device display/audio preferences (DataStore-backed; the server is
 * unit-agnostic). Field names, enum entries, and defaults match the Android
 * sources 1:1. Defaults are US/imperial + coach-audio-on to match prior behavior.
 */

@Serializable
data class UnitPreferences(
    val height: HeightUnit = HeightUnit.FEET_INCHES,
    val weight: WeightUnit = WeightUnit.POUNDS,
    val temperature: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
)

enum class HeightUnit { FEET_INCHES, CENTIMETERS }

enum class WeightUnit { POUNDS, KILOGRAMS }

enum class TemperatureUnit { FAHRENHEIT, CELSIUS }

/**
 * On-device audio settings for the workout coach (IMPL-COACH PR2). Both default
 * on — the coach is meant to be hands-free — and are stored locally only.
 */
@Serializable
data class CoachAudioSettings(
    /** Play a beep over the headphones when a rest period ends. */
    val restBeep: Boolean = true,
    /** Speak the exercise, weight, and reps aloud at the start of each set. */
    val voiceAnnouncements: Boolean = true,
)
