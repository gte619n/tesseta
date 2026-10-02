package com.gte619n.healthfitness.shared.domain.medications

import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/medications/ReminderModels.kt
 * (D3: Moshi→kotlinx.serialization).
 *
 * `java.time.LocalTime` swapped for kotlinx-datetime. `LocalTime.parse` maps
 * directly (both parse ISO "HH:mm"); `java.time.LocalTime.NOON` has no
 * kotlinx-datetime constant so it is spelled `LocalTime(12, 0)` — same value.
 * Field names and nullability match the Android source 1:1.
 *
 * Medication-reminder configuration, mirroring the backend's
 * `GET/PUT /api/me/medications/reminder-settings` contract: a master switch,
 * the user's default fire time per [TimeWindow] ("HH:mm" strings on the wire),
 * and optional per-medication overrides (mute and/or custom slot times).
 *
 * Resolution for "when does medication M's `window` slot remind?":
 * per-medication override time → user window time → built-in default.
 */
@Serializable
data class ReminderSettings(
    val enabled: Boolean = true,
    val windowTimes: Map<TimeWindow, String> = DEFAULT_WINDOW_TIMES,
    val perMedication: Map<String, MedicationReminderOverride> = emptyMap(),
) {
    fun timeFor(medicationId: String, window: TimeWindow): LocalTime {
        val custom = perMedication[medicationId]?.times?.get(window)
        val user = windowTimes[window]
        return parseTime(custom)
            ?: parseTime(user)
            ?: parseTime(DEFAULT_WINDOW_TIMES[window])
            ?: LocalTime(12, 0)
    }

    fun enabledFor(medicationId: String): Boolean {
        if (!enabled) return false
        return perMedication[medicationId]?.enabled ?: true
    }

    companion object {
        val DEFAULT_WINDOW_TIMES: Map<TimeWindow, String> = mapOf(
            TimeWindow.MORNING to "06:00",
            TimeWindow.AFTERNOON to "12:00",
            TimeWindow.EVENING to "18:00",
            TimeWindow.BEDTIME to "21:30",
        )

        private fun parseTime(value: String?): LocalTime? =
            value?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    }
}

/** Per-medication override: mute it and/or pin slots to custom times. */
@Serializable
data class MedicationReminderOverride(
    val enabled: Boolean = true,
    val times: Map<TimeWindow, String> = emptyMap(),
)
