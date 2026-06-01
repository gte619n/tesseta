package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.IntensityKind
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// Display formatting helpers for the workout-program screens. Pure functions,
// kept out of the composables so they stay previewable and testable.

private val DAY_LABELS = mapOf(
    DayOfWeek.MON to "Mon",
    DayOfWeek.TUE to "Tue",
    DayOfWeek.WED to "Wed",
    DayOfWeek.THU to "Thu",
    DayOfWeek.FRI to "Fri",
    DayOfWeek.SAT to "Sat",
    DayOfWeek.SUN to "Sun",
)

fun dayOfWeekLabel(day: DayOfWeek): String = DAY_LABELS[day] ?: day.name

/** "Mon · Wed · Fri" training-days summary. */
fun trainingDaysSummary(days: List<DayOfWeek>): String =
    if (days.isEmpty()) "No training days" else days.joinToString(" · ") { dayOfWeekLabel(it) }

/** "MON 6/2" style caps-mono label for a scheduled session date. */
fun scheduledDateLabel(date: LocalDate): String {
    val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US).uppercase()
    return "$dow ${date.monthValue}/${date.dayOfMonth}"
}

/**
 * "3 × 8–10 @ RPE 8 · rest 90s" for a strength prescription, or "12 min" for a
 * timed one. Pieces are omitted when their underlying value is absent.
 */
fun prescriptionSummary(p: Prescription): String {
    val parts = mutableListOf<String>()

    val duration = p.durationSeconds
    if (duration != null && (p.sets == null && p.repsMin == null)) {
        parts += durationLabel(duration)
    } else {
        val setsRep = setsRepsLabel(p)
        if (setsRep != null) parts += setsRep
    }

    intensityLabel(p)?.let { parts += "@ $it" }
    p.restSeconds?.let { parts += "rest ${restLabel(it)}" }
    p.tempo?.takeIf { it.isNotBlank() }?.let { parts += "tempo $it" }

    return parts.joinToString(" · ")
}

private fun setsRepsLabel(p: Prescription): String? {
    val reps = repsLabel(p.repsMin, p.repsMax)
    return when {
        p.sets != null && reps != null -> "${p.sets} × $reps"
        p.sets != null -> "${p.sets} sets"
        reps != null -> reps
        else -> null
    }
}

private fun repsLabel(min: Int?, max: Int?): String? = when {
    min != null && max != null && min != max -> "$min–$max"
    min != null -> "$min"
    max != null -> "$max"
    else -> null
}

private fun intensityLabel(p: Prescription): String? {
    val intensity = p.intensity ?: return null
    val value = intensity.value
    return when (intensity.kind) {
        IntensityKind.RPE -> value?.let { "RPE ${trimNumber(it)}" }
        IntensityKind.PERCENT_1RM -> value?.let { "${trimNumber(it)}% 1RM" }
        IntensityKind.NONE -> null
    }
}

private fun durationLabel(seconds: Int): String = when {
    seconds % 60 == 0 -> "${seconds / 60} min"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

private fun restLabel(seconds: Int): String =
    if (seconds % 60 == 0 && seconds >= 60) "${seconds / 60}m" else "${seconds}s"

private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
