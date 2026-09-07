package com.gte619n.healthfitness.feature.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ProgressionDirection
import kotlin.math.abs
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
 * Index of the first exercise that still has unlogged prescribed sets. Falls
 * back to 0 when every exercise is complete or there are none.
 */
fun WorkoutSessionDraft.firstIncompleteStepIndex(): Int {
    val idx = sessionSteps().indexOfFirst { step ->
        (logged[step.key]?.size ?: 0) < (step.prescription.sets ?: 1)
    }
    return if (idx >= 0) idx else 0
}

/**
 * Where the coach should (re)open — used as the pager's initial page whenever the
 * screen is recreated (resume from the notification, process death, config
 * change). Unlike [firstIncompleteStepIndex], this never jumps *backward* past
 * exercises you've already worked: it resumes at the first exercise with sets
 * still to do, searching from the furthest exercise you've logged anything in.
 *
 * That distinction is the fix for the "resume sends me back to the warmup" bug:
 * the "Next" control advances the pager without logging, so a mobility/stretch
 * you did but didn't log stays "incomplete" — and [firstIncompleteStepIndex]
 * would drag focus back to it on every resume, even though you're mid-way
 * through the lifts. Here a skipped-but-passed step is left behind. With nothing
 * logged yet we open at the top.
 */
fun WorkoutSessionDraft.resumeStepIndex(): Int {
    val steps = sessionSteps()
    if (steps.isEmpty()) return 0
    val furthestTouched = steps.indexOfLast { step -> (logged[step.key]?.size ?: 0) > 0 }
    if (furthestTouched < 0) return 0
    val next = (furthestTouched until steps.size).firstOrNull { i ->
        (logged[steps[i].key]?.size ?: 0) < (steps[i].prescription.sets ?: 1)
    }
    // Everything from here on is done → stay on the last exercise you touched
    // (the finish flow takes over).
    return next ?: furthestTouched
}

/**
 * The engine's intended reps for the upcoming set (IMPL-PROG-02 F5/D5), or null
 * when there's no engine decision to honour (a static program → keep the existing
 * "carry last session" behaviour). When the engine raised the load
 * ([ProgressionDirection.UP]) the target resets to the BOTTOM of the rep band so
 * the heavier weight stays achievable; otherwise it's the top of the band (work
 * reps up before the next jump).
 */
fun targetReps(prescription: Prescription): Int? {
    prescription.rationale ?: return null
    return if (prescription.rationale?.direction == ProgressionDirection.UP) {
        prescription.repsMin ?: prescription.repsMax
    } else {
        prescription.repsMax ?: prescription.repsMin
    }
}

/**
 * The prefill for the next, not-yet-logged set of a prescription — the value
 * the logger shows on the pending row and the coach announces. Precedence
 * (IMPL-PROG-02 D1): what was carried within this session (the last logged set),
 * then the ENGINE PREDICTION ([Prescription.targetWeightLbs] / [targetReps]) as
 * the authoritative next target, then — only if there's no prediction — the final
 * set of the previous session ([lastSets]).
 *
 * <p>Making the prediction win over last-session actual is the root-cause fix for
 * the "announcement/notification switched a second later" race: the shown number
 * no longer depends on the async [lastSets] fetch landing.
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
    // The final set from the last time this exercise was done. We anchor every
    // pending set on that top working set ("start where you left off") rather
    // than index-matching set-for-set, so opening the exercise fresh proposes
    // last session's heaviest/last load, not its warm-up first set.
    val lastTime = lastSets[prescription.exerciseId]?.lastOrNull()
    return if (prescription.isTimed) {
        SetPrefill(
            durationSeconds = previous?.durationSeconds
                ?: lastTime?.durationSeconds
                ?: prescription.durationSeconds,
        )
    } else {
        SetPrefill(
            weightLbs = previous?.weightLbs ?: prescription.targetWeightLbs ?: lastTime?.weightLbs,
            reps = previous?.reps ?: targetReps(prescription) ?: lastTime?.reps
                ?: prescription.repsMax ?: prescription.repsMin,
        )
    }
}

/**
 * How the shown target compares to what the athlete actually did last time
 * (IMPL-PROG-02 F1/D2/D9). Rendered as a neutral accent ▲/▼ chip on the number —
 * deliberately distinct from the green/red HIT/MISS outcome coloring. Null when
 * there's no last-time value or the target is unchanged.
 */
data class TargetAdjustment(val up: Boolean, val label: String)

/** Weight target vs. last-session actual weight for this exercise. */
fun weightAdjustment(
    prescription: Prescription,
    lastSets: Map<String, List<LoggedSet>>,
): TargetAdjustment? {
    // Only flag a change the ENGINE made — a static program target isn't an "adjustment".
    prescription.rationale ?: return null
    if (prescription.isBodyweight) return null
    val target = prescription.targetWeightLbs ?: return null
    val last = lastSets[prescription.exerciseId]?.lastOrNull()?.weightLbs ?: return null
    val delta = target - last
    if (abs(delta) < 0.5) return null
    val mag = abs(delta)
    val num = if (mag == mag.toLong().toDouble()) mag.toLong().toString() else mag.toString()
    return TargetAdjustment(up = delta > 0, label = "${if (delta > 0) "+" else "−"}$num lb")
}

/** Rep target vs. last-session actual reps for this exercise. */
fun repsAdjustment(
    prescription: Prescription,
    lastSets: Map<String, List<LoggedSet>>,
): TargetAdjustment? {
    val target = targetReps(prescription) ?: return null
    val last = lastSets[prescription.exerciseId]?.lastOrNull()?.reps ?: return null
    val delta = target - last
    if (delta == 0) return null
    return TargetAdjustment(up = delta > 0, label = "${if (delta > 0) "+" else "−"}${abs(delta)} reps")
}

/**
 * True once every prescribed set of every exercise in the session has been
 * logged. Drives the auto-complete flow (skip the last rest, jump straight to
 * the summary). [projected] lets the caller test the map it is *about* to
 * persist, before the Room round-trip lands it back on the draft.
 */
fun WorkoutSessionDraft.isComplete(
    projected: Map<PrescriptionKey, List<LoggedSet>> = logged,
): Boolean {
    val steps = sessionSteps()
    if (steps.isEmpty()) return false
    return steps.all { step ->
        (projected[step.key]?.size ?: 0) >= (step.prescription.sets ?: 1)
    }
}

/**
 * How a logged value compares to what was prescribed — the red/green/black
 * signal on a completed set (target vs. achieved). [HIT] met or beat the
 * target, [MISS] fell short, [NEUTRAL] when there's nothing to compare against
 * (no target, or the value wasn't recorded). Pure so the UI just maps it to a
 * colour.
 */
enum class TargetOutcome { HIT, MISS, NEUTRAL }

/** Reps achieved vs. the prescribed rep range: short of [repsMin] is a miss. */
fun repsOutcome(prescription: Prescription, reps: Int?): TargetOutcome {
    val min = prescription.repsMin ?: prescription.repsMax ?: return TargetOutcome.NEUTRAL
    if (reps == null) return TargetOutcome.NEUTRAL
    return if (reps >= min) TargetOutcome.HIT else TargetOutcome.MISS
}

/** Weight achieved vs. the prescribed load: under the target is a miss. */
fun weightOutcome(prescription: Prescription, weightLbs: Double?): TargetOutcome {
    val target = prescription.targetWeightLbs ?: return TargetOutcome.NEUTRAL
    if (weightLbs == null) return TargetOutcome.NEUTRAL
    return if (weightLbs >= target) TargetOutcome.HIT else TargetOutcome.MISS
}

// ---- RIR (reps-in-reserve) capture — the RPE successor, inference-first. ----
//
// The progression engine wants effort as reps-in-reserve, but asking every set
// is friction. So the logger prompts only the LAST working set of an exercise,
// one-tap, pre-filled with an inferred value the user just confirms (or nudges).
// The stored source flips to `REPORTED` the moment the user taps a chip.

/** The RIR chips offered on the last-set prompt. `5` is shown as "5+". */
val RIR_CHOICES: List<Int> = listOf(0, 1, 2, 3, 4, 5)

/** [LoggedSet.rirSource] value written when the user taps a RIR chip themselves. */
const val RIR_SOURCE_REPORTED = "REPORTED"

/** [LoggedSet.rirSource] value for a value we inferred from the rep outcome. */
const val RIR_SOURCE_INFERRED_TARGET = "INFERRED_TARGET"

/**
 * The RIR we pre-select on the last set's chip row, inference-first: if the set
 * already carries a reported [LoggedSet.rir] (re-opened/edited), keep it; else
 * infer from how the reps landed against the target — hitting the top of the
 * range suggests a couple left in the tank (2), falling short suggests failure
 * (0), mid-range a modest reserve (1). Clamped into [RIR_CHOICES].
 */
fun inferredRir(prescription: Prescription, set: LoggedSet): Int {
    set.rir?.let { return it.toInt().coerceIn(RIR_CHOICES.first(), RIR_CHOICES.last()) }
    val reps = set.reps
    val target = prescription.repsMax ?: prescription.repsMin
    return when {
        reps == null || target == null -> 2
        reps < target -> 0
        reps == target -> 1
        else -> 2
    }
}

/**
 * The spoken "rest 90 seconds" cue announced when a rest countdown starts (PR2
 * voice announcements), phrased in minutes/seconds so a long rest doesn't read
 * as "Rest 120 seconds".
 */
fun restAnnouncement(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    val phrase = when {
        m > 0 && s > 0 -> "$m ${if (m > 1) "minutes" else "minute"} $s seconds"
        m > 0 -> "$m ${if (m > 1) "minutes" else "minute"}"
        else -> "$s seconds"
    }
    return "Rest $phrase."
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
    reps: Int? = targetReps(prescription) ?: prescription.repsMax ?: prescription.repsMin,
): String? {
    val name = prescription.exercise?.name?.takeIf { it.isNotBlank() } ?: return null
    if (prescription.isTimed) {
        val seconds = prescription.durationSeconds ?: return "$name."
        return "$name. $seconds second hold."
    }
    val pieces = mutableListOf<String>()
    // IMPL-PROG-02 F6: say "body weight" ONLY for a real bodyweight movement.
    // A weighted lift with no known load (lbs 0/null) omits the weight rather than
    // announcing the impossible "body weight".
    weightLbs?.let { lbs ->
        val rounded = if (lbs == lbs.toLong().toDouble()) lbs.toLong().toString() else lbs.toString()
        when {
            prescription.isBodyweight -> pieces += "body weight"
            lbs > 0.0 -> pieces += "$rounded pounds"
        }
    }
    if (weightLbs == null && prescription.isBodyweight) {
        pieces += "body weight"
    }
    reps?.let { r ->
        pieces += if (r == 1) "1 rep" else "$r reps"
    }
    return if (pieces.isEmpty()) "$name." else "$name. ${pieces.joinToString(", ")}."
}

/**
 * The spoken pre-roll before a hold auto-starts in the guided stretch flow, e.g.
 * "Get ready for Pigeon Pose. 45 second hold." Announced as the coach advances
 * from one completed hold into the next. Returns null when there's no exercise
 * name to announce.
 */
fun getReadyAnnouncement(prescription: Prescription): String? {
    val name = prescription.exercise?.name?.takeIf { it.isNotBlank() } ?: return null
    val seconds = prescription.durationSeconds
    return if (seconds != null) "Get ready for $name. $seconds second hold." else "Get ready for $name."
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
