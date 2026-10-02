package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import com.gte619n.healthfitness.shared.domain.workouts.program.IntensityKind
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.Prescription
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgressionDirection
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutDay

/**
 * IMPL-IOS-01 Phase 3 Wave D — KMP port of the Android
 * `feature-workouts/.../program/ProgramFormat.kt`. Pure display formatting for
 * the workout-program screens, single-sourced so both clients render set/rep/
 * weight/duration identically (anti-drift). The SwiftUI views mirror these into
 * local Swift helpers only until the XCFramework is built; the Swift-side
 * formatting test guards parity with these outputs.
 */

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

/**
 * The concrete, coaching-first target line: "45 lb · 4 × 15 · rest 90s". Leads
 * with the engine's actual load and a single FIXED rep target (no rep range / RPE
 * / tempo clutter). A bodyweight movement reads "BW"; a timed hold reads its
 * duration. Pieces omitted when their value is absent. ▲/▼ trend is rendered by
 * the caller from [Prescription.rationale].
 */
fun prescriptionTargetLine(p: Prescription): String {
    val parts = mutableListOf<String>()
    if (p.isTimed) {
        p.durationSeconds?.let { parts += durationLabel(it) }
    } else {
        weightTargetLabel(p)?.let { parts += it }
        setsRepsTargetLabel(p)?.let { parts += it }
    }
    p.restSeconds?.let { parts += "rest ${restLabel(it)}" }
    return parts.joinToString(" · ")
}

/**
 * The single, fixed rep target the athlete aims for this set — no range. Right
 * after a load increase ([ProgressionDirection.UP]) that's the BOTTOM of the band
 * (the heavier load stays achievable); otherwise the TOP (work reps up before the
 * next jump). Null only when the prescription carries no rep target at all.
 */
fun fixedRepTarget(p: Prescription): Int? =
    if (p.rationale?.direction == ProgressionDirection.UP) {
        p.repsMin ?: p.repsMax
    } else {
        p.repsMax ?: p.repsMin
    }

private fun weightTargetLabel(p: Prescription): String? {
    val lbs = p.targetWeightLbs
    return when {
        lbs != null && lbs > 0.0 -> "${trimNumber(lbs)} lb"
        p.isBodyweight -> "BW"
        else -> null
    }
}

private fun setsRepsTargetLabel(p: Prescription): String? {
    val reps = fixedRepTarget(p)
    return when {
        p.sets != null && reps != null -> "${p.sets} × $reps"
        p.sets != null -> "${p.sets} sets"
        reps != null -> "$reps reps"
        else -> null
    }
}

/** "RPE 8" / "80% 1RM" intensity descriptor, null when NONE / absent. */
fun intensityLabel(p: Prescription): String? {
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

/**
 * "135 lb × 8 (×2) · 135 lb × 7" — the actual sets performed, when present.
 * Null when the prescription has no logged sets. Consecutive identical sets are
 * collapsed with "(×N)"; a zero weight reads "BW".
 */
fun loggedSetsSummary(sets: List<LoggedSet>): String? {
    if (sets.isEmpty()) return null
    val formatted = sets.map { loggedSetLabel(it) }
    val parts = mutableListOf<String>()
    var i = 0
    while (i < formatted.size) {
        var j = i + 1
        while (j < formatted.size && formatted[j] == formatted[i]) j++
        val count = j - i
        parts += if (count > 1) "${formatted[i]} (×$count)" else formatted[i]
        i = j
    }
    return parts.joinToString(" · ")
}

/** Convenience overload for a prescription's own logged sets. */
fun loggedSetsSummary(p: Prescription): String? = loggedSetsSummary(p.loggedSets)

private fun loggedSetLabel(s: LoggedSet): String {
    val lbs = s.weightLbs
    val weight = when {
        lbs == null -> null
        lbs == 0.0 -> "BW"
        else -> "${trimNumber(lbs)} lb"
    }
    val reps = s.reps?.let { "$it" }
    return when {
        weight != null && reps != null -> "$weight × $reps"
        weight != null -> weight
        reps != null -> "$reps reps"
        s.durationSeconds != null -> durationLabel(s.durationSeconds)
        else -> "—"
    }
}

/** Total prescribed exercises across all blocks of a day. */
fun exerciseCount(day: WorkoutDay): Int = day.blocks.sumOf { it.prescriptions.size }

/** "5 exercises" / "1 exercise" / "No exercises" count label for a day. */
fun exerciseCountLabel(day: WorkoutDay): String = when (val n = exerciseCount(day)) {
    0 -> "No exercises"
    1 -> "1 exercise"
    else -> "$n exercises"
}
