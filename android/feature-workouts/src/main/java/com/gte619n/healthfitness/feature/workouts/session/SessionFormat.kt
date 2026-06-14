package com.gte619n.healthfitness.feature.workouts.session

import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft

// Display formatting helpers for the active-session logger. Pure functions,
// kept out of the composables so they stay previewable and testable (same
// pattern as program/ProgramFormat.kt).

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
