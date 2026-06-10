package com.gte619n.healthfitness.domain.medications

import java.time.LocalTime

/**
 * Medication-reminder configuration, mirroring the backend's
 * `GET/PUT /api/me/medications/reminder-settings` contract: a master switch,
 * the user's default fire time per [TimeWindow] ("HH:mm" strings on the wire),
 * and optional per-medication overrides (mute and/or custom slot times).
 *
 * Resolution for "when does medication M's `window` slot remind?":
 * per-medication override time → user window time → built-in default.
 */
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
            ?: LocalTime.NOON
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
data class MedicationReminderOverride(
    val enabled: Boolean = true,
    val times: Map<TimeWindow, String> = emptyMap(),
)

/** One medication dose included in a reminder. */
data class ReminderDose(
    val medicationId: String,
    val name: String,
    val window: TimeWindow,
    val dose: Double,
    val unit: String,
)

/** One scheduled reminder: every dose that fires at the same local time. */
data class PlannedReminder(
    val at: java.time.LocalDateTime,
    val doses: List<ReminderDose>,
)
