package com.gte619n.healthfitness.mobile.workouts

import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers.RestTimer
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import java.time.Instant

/**
 * ADR-0012 Decision 6 — pure derivation of the foreground notification's
 * content from the draft + rest-timer state. Kept Android-free (no
 * `Notification`/`Context`) so the elapsed/rest anchors, current-exercise
 * derivation, and text formatting are plain-JVM testable;
 * [WorkoutSessionService] only wraps the result in a `NotificationCompat`
 * builder.
 *
 * Time display uses the notification chronometer rather than per-second
 * re-posts: workout mode anchors a count-**up** at [Content.elapsedSinceMillis]
 * (session start), rest mode a count-**down** to [Content.countdownToMillis]
 * (rest end). Exactly one anchor is non-null.
 */
object WorkoutSessionNotificationContent {

    data class Content(
        val title: String,
        val text: String,
        /** Epoch millis the elapsed chronometer counts up from (workout mode). */
        val elapsedSinceMillis: Long?,
        /** Epoch millis the rest chronometer counts down to (rest mode). */
        val countdownToMillis: Long?,
    )

    fun from(draft: WorkoutSessionDraft, rest: RestTimer?, now: Instant): Content {
        val next = currentExerciseName(draft)
        return if (rest != null && rest.isRunning(now)) {
            Content(
                title = draft.scheduled.dayLabel,
                text = if (next != null) "Resting — next: $next" else "Resting",
                elapsedSinceMillis = null,
                countdownToMillis = rest.endsAt.toEpochMilli(),
            )
        } else {
            Content(
                title = draft.scheduled.dayLabel,
                text = if (next != null) {
                    "Now: $next · ${setsLoggedLabel(draft.totalLoggedSets)}"
                } else {
                    "All sets logged — finish when ready"
                },
                elapsedSinceMillis = draft.startedAt.toEpochMilli(),
                countdownToMillis = null,
            )
        }
    }

    /**
     * The exercise the user is on: the first prescription (blocks then
     * prescriptions in `orderIndex` order) with fewer logged sets than
     * prescribed (`sets = null` counts as one). Null once every prescription
     * is fully logged — or when the draft has no session snapshot at all.
     */
    fun currentExerciseName(draft: WorkoutSessionDraft): String? {
        val day = draft.scheduled.session ?: return null
        for (block in day.blocks.sortedBy { it.orderIndex }) {
            for (prescription in block.prescriptions.sortedBy { it.orderIndex }) {
                val key = PrescriptionKey(block.blockId, prescription.orderIndex)
                val logged = draft.logged[key]?.size ?: 0
                if (logged < (prescription.sets ?: 1)) {
                    return prescription.exercise?.name ?: prescription.exerciseId
                }
            }
        }
        return null
    }

    fun setsLoggedLabel(count: Int): String =
        if (count == 1) "1 set logged" else "$count sets logged"
}
