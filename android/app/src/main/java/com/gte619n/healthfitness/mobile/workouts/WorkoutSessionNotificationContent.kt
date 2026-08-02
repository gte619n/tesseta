package com.gte619n.healthfitness.mobile.workouts

import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers.RestTimer
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
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

    /** The exercise + set the user is on, with its prescribed/carried load. */
    data class CurrentSet(
        val name: String,
        val setNumber: Int,
        val totalSets: Int,
        /** "135 lb × 12" / "45s hold", or null when nothing to show. */
        val loadLabel: String?,
    ) {
        /** "Bench Press · Set 2 of 4 · 135 lb × 12" — the notification's "where you are" line. */
        fun describe(): String {
            val progress = "$name · Set $setNumber of $totalSets"
            return if (loadLabel != null) "$progress · $loadLabel" else progress
        }
    }

    fun from(
        draft: WorkoutSessionDraft,
        rest: RestTimer?,
        now: Instant,
    ): Content {
        val current = currentSet(draft)
        return when {
            rest != null && rest.isRunning(now) -> Content(
                title = draft.scheduled.dayLabel,
                text = if (current != null) "Resting — next: ${current.describe()}" else "Resting",
                elapsedSinceMillis = null,
                countdownToMillis = rest.endsAt.toEpochMilli(),
            )
            else -> Content(
                title = draft.scheduled.dayLabel,
                text = if (current != null) {
                    "Now: ${current.describe()}"
                } else {
                    "All sets logged — finish when ready"
                },
                elapsedSinceMillis = draft.startedAt.toEpochMilli(),
                countdownToMillis = null,
            )
        }
    }

    /**
     * The set the user is on: the first prescription (blocks then prescriptions
     * in `orderIndex` order) with fewer logged sets than prescribed (`sets =
     * null` counts as one). Null once every prescription is fully logged — or
     * when the draft has no session snapshot at all.
     */
    fun currentSet(draft: WorkoutSessionDraft): CurrentSet? {
        val day = draft.scheduled.session ?: return null
        for (block in day.blocks.sortedBy { it.orderIndex }) {
            for (prescription in block.prescriptions.sortedBy { it.orderIndex }) {
                val key = PrescriptionKey(block.blockId, prescription.orderIndex)
                val logged = draft.logged[key].orEmpty()
                val total = prescription.sets ?: 1
                if (logged.size < total) {
                    return CurrentSet(
                        name = prescription.exercise?.name ?: prescription.exerciseId,
                        setNumber = logged.size + 1,
                        totalSets = total,
                        loadLabel = loadLabel(prescription, logged),
                    )
                }
            }
        }
        return null
    }

    /** Convenience for callers that only need the current exercise's name. */
    fun currentExerciseName(draft: WorkoutSessionDraft): String? = currentSet(draft)?.name

    /** The carried (or prescribed) load for the upcoming set — the same numbers the logger prefills. */
    private fun loadLabel(prescription: Prescription, logged: List<LoggedSet>): String? {
        val previous = logged.lastOrNull()
        if (prescription.isTimed) {
            val seconds = previous?.durationSeconds ?: prescription.durationSeconds ?: return null
            return "${seconds}s hold"
        }
        val weight = previous?.weightLbs ?: prescription.targetWeightLbs
        val reps = previous?.reps ?: prescription.repsMax ?: prescription.repsMin
        val weightPart = weight?.let { if (it == 0.0) "body weight" else "${formatWeight(it)} lb" }
        return when {
            weightPart != null && reps != null -> "$weightPart × $reps"
            weightPart != null -> weightPart
            reps != null -> "$reps reps"
            else -> null
        }
    }

    private fun formatWeight(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    fun setsLoggedLabel(count: Int): String =
        if (count == 1) "1 set logged" else "$count sets logged"
}
