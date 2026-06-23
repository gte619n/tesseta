package com.gte619n.healthfitness.feature.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.Block
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
 * The spoken cue for an exercise at set start (PR2 voice announcements), e.g.
 * "Back Squat. 185 pounds, 8 reps." or, for a timed hold, "Plank. 45 second
 * hold." Returns null when there's no exercise name to announce. Weight/reps
 * pieces are dropped when the prescription doesn't specify them.
 */
fun coachAnnouncement(prescription: Prescription): String? {
    val name = prescription.exercise?.name?.takeIf { it.isNotBlank() } ?: return null
    if (prescription.isTimed) {
        val seconds = prescription.durationSeconds ?: return "$name."
        return "$name. $seconds second hold."
    }
    val pieces = mutableListOf<String>()
    prescription.targetWeightLbs?.let { lbs ->
        val rounded = if (lbs == lbs.toLong().toDouble()) lbs.toLong().toString() else lbs.toString()
        pieces += if (lbs == 0.0) "body weight" else "$rounded pounds"
    }
    (prescription.repsMax ?: prescription.repsMin)?.let { reps ->
        pieces += if (reps == 1) "1 rep" else "$reps reps"
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
