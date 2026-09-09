package com.gte619n.healthfitness.mobile.workouts

import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers.Kind
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers.RestTimer
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.session.prefillFor
import com.gte619n.healthfitness.feature.workouts.session.restCountdownLabel
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
        // IMPL-PROG-02 D1 / #4: the prior-session actuals the logger prefills from.
        // Passed in so the notification's "N lb × R" is computed by the SAME
        // [prefillFor] the coaching UI and the voice cue use — they can never
        // disagree. Empty when unknown (offline / not yet fetched).
        lastSets: Map<String, List<LoggedSet>> = emptyMap(),
    ): Content {
        val current = currentSet(draft, lastSets)
        return when {
            rest != null && rest.isRunning(now) -> {
                // The get-ready pre-roll before a timed hold and the between-sets
                // rest share the countdown; only the verb differs.
                val verb = if (rest.kind == Kind.GET_READY) "Get ready" else "Resting"
                Content(
                    title = draft.scheduled.dayLabel,
                    text = if (current != null) "$verb — next: ${current.describe()}" else verb,
                    elapsedSinceMillis = null,
                    countdownToMillis = rest.endsAt?.toEpochMilli(),
                )
            }
            // A paused get-ready pre-roll: the chronometer can't count a frozen
            // clock, so bake the time-left into the text (buildNotification's
            // no-chronometer branch renders it).
            rest != null && rest.isPaused -> {
                val remaining = restCountdownLabel(rest.remainingSeconds(now))
                Content(
                    title = draft.scheduled.dayLabel,
                    text = if (current != null) {
                        "Paused · $remaining — next: ${current.describe()}"
                    } else {
                        "Paused · $remaining"
                    },
                    elapsedSinceMillis = null,
                    countdownToMillis = null,
                )
            }
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
    fun currentSet(
        draft: WorkoutSessionDraft,
        lastSets: Map<String, List<LoggedSet>> = emptyMap(),
    ): CurrentSet? {
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
                        loadLabel = loadLabel(prescription, logged, lastSets),
                    )
                }
            }
        }
        return null
    }

    /** Convenience for callers that only need the current exercise's name. */
    fun currentExerciseName(draft: WorkoutSessionDraft): String? = currentSet(draft)?.name

    /**
     * The carried (or prescribed) load for the upcoming set — resolved by the same
     * [prefillFor] the logger's pending row and the voice cue use, so the
     * notification's numbers always match the coaching UI (#4). "body weight" is
     * shown only for a real bodyweight movement; a weighted lift with no known
     * load omits the weight rather than lying.
     */
    private fun loadLabel(
        prescription: Prescription,
        logged: List<LoggedSet>,
        lastSets: Map<String, List<LoggedSet>>,
    ): String? {
        val prefill = prefillFor(prescription, logged, lastSets)
        if (prescription.isTimed) {
            val seconds = prefill.durationSeconds ?: return null
            return "${seconds}s hold"
        }
        val weight = prefill.weightLbs
        val reps = prefill.reps
        val weightPart = when {
            prescription.isBodyweight -> "body weight"
            weight != null && weight > 0.0 -> "${formatWeight(weight)} lb"
            else -> null
        }
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
