package com.gte619n.healthfitness.feature.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft

// Display formatting helpers for the active-session logger. Pure functions,
// kept out of the composables so they stay previewable and testable (same
// pattern as program/ProgramFormat.kt).

/**
 * One exercise the coach steps through: a [prescription] inside its [block],
 * plus the [key] its logged sets are stored under. The session is flattened into
 * an ordered list of these for the one-exercise-at-a-time pager.
 */
data class SessionStep(
    val block: Block,
    val prescription: Prescription,
    val key: PrescriptionKey,
)

/** Every prescription across the session's blocks, in display order. */
fun WorkoutSessionDraft.sessionSteps(): List<SessionStep> {
    val day = scheduled.session ?: return emptyList()
    return day.blocks.sortedBy { it.orderIndex }.flatMap { block ->
        block.prescriptions.sortedBy { it.orderIndex }.map { rx ->
            SessionStep(block, rx, PrescriptionKey(block.blockId, rx.orderIndex))
        }
    }
}

/**
 * Index of the first exercise that still has unlogged prescribed sets — where
 * the coach should open (and re-open on resume). Falls back to 0 when every
 * exercise is complete or there are none.
 */
fun WorkoutSessionDraft.firstIncompleteStepIndex(): Int {
    val idx = sessionSteps().indexOfFirst { step ->
        (logged[step.key]?.size ?: 0) < (step.prescription.sets ?: 1)
    }
    return if (idx >= 0) idx else 0
}

/**
 * The prefill for the next, not-yet-logged set of a prescription — the value
 * the logger shows on the pending row and the coach announces. Precedence:
 * what was carried within this session (the last logged set), then the literal
 * previous session ([lastSets], IMPL-COACH PR2), then the designed target.
 * Shared by the UI (display) and the ViewModel (the actual logged set) so both
 * agree on "what to lift next". A timed exercise carries a held duration
 * instead of weight/reps.
 */
data class SetPrefill(
    val weightLbs: Double? = null,
    val reps: Int? = null,
    val durationSeconds: Int? = null,
)

fun prefillFor(
    prescription: Prescription,
    logged: List<LoggedSet>,
    lastSets: Map<String, List<LoggedSet>>,
): SetPrefill {
    val previous = logged.lastOrNull()
    // The matching set (by index) from the last time this exercise was done.
    val lastTime = lastSets[prescription.exerciseId]?.getOrNull(logged.size)
    return if (prescription.isTimed) {
        SetPrefill(
            durationSeconds = previous?.durationSeconds
                ?: lastTime?.durationSeconds
                ?: prescription.durationSeconds,
        )
    } else {
        SetPrefill(
            weightLbs = previous?.weightLbs ?: lastTime?.weightLbs ?: prescription.targetWeightLbs,
            reps = previous?.reps ?: lastTime?.reps ?: prescription.repsMax ?: prescription.repsMin,
        )
    }
}

/**
 * The spoken cue for an exercise at set start (PR2 voice announcements), e.g.
 * "Back Squat. 185 pounds, 8 reps." or, for a timed hold, "Plank. 45 second
 * hold." Returns null when there's no exercise name to announce. Weight/reps
 * default to the prescription's target but callers pass the effective prefill
 * (so a set carried from last time announces its real load); pieces are dropped
 * when neither prefill nor prescription specifies them.
 */
fun coachAnnouncement(
    prescription: Prescription,
    weightLbs: Double? = prescription.targetWeightLbs,
    reps: Int? = prescription.repsMax ?: prescription.repsMin,
): String? {
    val name = prescription.exercise?.name?.takeIf { it.isNotBlank() } ?: return null
    if (prescription.isTimed) {
        val seconds = prescription.durationSeconds ?: return "$name."
        return "$name. $seconds second hold."
    }
    val pieces = mutableListOf<String>()
    weightLbs?.let { lbs ->
        val rounded = if (lbs == lbs.toLong().toDouble()) lbs.toLong().toString() else lbs.toString()
        pieces += if (lbs == 0.0) "body weight" else "$rounded pounds"
    }
    reps?.let { r ->
        pieces += if (r == 1) "1 rep" else "$r reps"
    }
    return if (pieces.isEmpty()) "$name." else "$name. ${pieces.joinToString(", ")}."
}

/** "47:32" / "1:02:10" count-up label for the session's elapsed header. */
fun elapsedLabel(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** "1:30" mm:ss label for the rest countdown. */
fun restCountdownLabel(remainingSeconds: Long): String {
    val s = remainingSeconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * (prescriptions with at least one logged set, total prescriptions) — the
 * "Exercises 3 / 5" line on the finish summary.
 */
fun loggedExerciseCounts(draft: WorkoutSessionDraft): Pair<Int, Int> {
    val day = draft.scheduled.session ?: return 0 to 0
    var logged = 0
    var total = 0
    for (block in day.blocks) {
        for (prescription in block.prescriptions) {
            total++
            val key = PrescriptionKey(block.blockId, prescription.orderIndex)
            if (!draft.logged[key].isNullOrEmpty()) logged++
        }
    }
    return logged to total
}
