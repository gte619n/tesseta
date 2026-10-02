package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.domain.workouts.program.Block
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.Prescription
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgressionDirection
import com.gte619n.healthfitness.shared.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft

/**
 * IMPL-IOS-01 Phase 3 Wave D(ii) — pure display/logic helpers for the shared
 * active-session logger, ported from Android
 * `feature-workouts/.../session/SessionFormat.kt`. Kept out of the ViewModel so
 * they stay independently testable and can be exercised on both platforms.
 * Only the subset the live logger + Live Activity need is ported (steps,
 * completion, prefill, RIR/effort capture); the Android target-adjustment /
 * outcome-colouring helpers are not required by the iOS session MVP.
 */

/**
 * One exercise the coach steps through: a [prescription] inside its [block], plus
 * the [key] its logged sets are stored under. The session flattens into an
 * ordered list of these for the one-exercise-at-a-time pager.
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
 * Where the coach should (re)open — the pager's initial page on resume. Never
 * jumps backward past exercises already worked: resumes at the first exercise
 * with sets still to do, searching from the furthest exercise anything was logged
 * in (the "resume sends me back to the warmup" fix). With nothing logged, opens
 * at the top.
 */
fun WorkoutSessionDraft.resumeStepIndex(): Int {
    val steps = sessionSteps()
    if (steps.isEmpty()) return 0
    val furthestTouched = steps.indexOfLast { step -> (logged[step.key]?.size ?: 0) > 0 }
    if (furthestTouched < 0) return 0
    val next = (furthestTouched until steps.size).firstOrNull { i ->
        (logged[steps[i].key]?.size ?: 0) < (steps[i].prescription.sets ?: 1)
    }
    return next ?: furthestTouched
}

/**
 * True once every prescribed set of every exercise has been logged. Drives the
 * auto-complete flow. [projected] lets the caller test the map it is *about* to
 * persist, before the round-trip lands it back on the draft.
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

/** Total prescribed sets across the whole session (the denominator for progress). */
fun WorkoutSessionDraft.totalPrescribedSets(): Int =
    sessionSteps().sumOf { it.prescription.sets ?: 1 }

// ---- RIR (reps-in-reserve) capture — the RPE successor, inference-first. ----

/** The RIR chips offered on the last-set prompt. `5` is shown as "5+". */
val RIR_CHOICES: List<Int> = listOf(0, 1, 2, 3, 4, 5)

/** [LoggedSet.rirSource] value written when the user taps a RIR chip themselves. */
const val RIR_SOURCE_REPORTED = "REPORTED"

/** [LoggedSet.rirSource] value for a value we inferred from the rep outcome. */
const val RIR_SOURCE_INFERRED_TARGET = "INFERRED_TARGET"

/**
 * The RIR we pre-select on the last set's chip row, inference-first: keep a
 * reported [LoggedSet.rir] if present; else infer from how the reps landed against
 * the target. Clamped into [RIR_CHOICES].
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

// ---- timed-exercise effort (IMPL-FIXPACK-01 Phase 4) ----

/** [LoggedSet.timedEffort]: the hold was too hard — could have done less. */
const val TIMED_EFFORT_LESS = "LESS"
/** [LoggedSet.timedEffort]: the hold was about right. */
const val TIMED_EFFORT_SAME = "SAME"
/** [LoggedSet.timedEffort]: the hold was too easy — could have held longer/more. */
const val TIMED_EFFORT_MORE = "MORE"

/** The three timed-effort choices, ordered least → most capability. */
val TIMED_EFFORT_CHOICES: List<String> = listOf(TIMED_EFFORT_LESS, TIMED_EFFORT_SAME, TIMED_EFFORT_MORE)

// ---- prefill precedence (IMPL-PROG-02) ----

/**
 * The engine's intended reps for the upcoming set, or null when there's no engine
 * decision (a static program → carry last session). On an engine load-raise (UP)
 * the target resets to the BOTTOM of the band; otherwise the top.
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
 * The prefill for the next, not-yet-logged set. Precedence (IMPL-PROG-02 D1):
 * weight carries within this session first, then the engine prediction
 * ([Prescription.targetWeightLbs]), then last session's final set. Reps LEAD with
 * the engine target (so the RIR gate + coach cue read a non-stale number), but
 * never propose fewer reps than already hit this session. Static programs fall
 * back to the carry. Timed exercises carry a held duration.
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
            reps = run {
                val engineTarget = targetReps(prescription)
                val carried = previous?.reps
                when {
                    engineTarget != null -> maxOf(engineTarget, carried ?: engineTarget)
                    else -> carried ?: lastTime?.reps ?: prescription.repsMax ?: prescription.repsMin
                }
            },
        )
    }
}
