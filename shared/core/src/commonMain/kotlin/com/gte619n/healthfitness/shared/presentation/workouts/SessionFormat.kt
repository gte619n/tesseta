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
 * IMPL-IOS-01 Phase G (view wiring) — a fully-computed, primitives-only display row
 * per exercise, so the SwiftUI logger renders without reaching into the Kotlin draft
 * `Map`/`Instant`/`Prescription` across the ObjC bridge (and the target-summary
 * formatting stays single-sourced + testable). The view rebuilds its `PrescriptionKey`
 * from [blockId] + [orderIndex] to call `toggleSet`.
 */
data class SessionRow(
    val blockId: String,
    val orderIndex: Int,
    /** The catalog exercise id — powers the per-exercise history popup. */
    val exerciseId: String,
    val name: String,
    val blockTitle: String,
    val targetSummary: String,
    val setsDone: Int,
    val setsTotal: Int,
    val isTimed: Boolean,
    val isCurrent: Boolean,
)

/** The session's exercises as display rows (order + current-step + logged counts resolved). */
fun WorkoutSessionDraft.sessionRows(): List<SessionRow> {
    val current = resumeStepIndex()
    return sessionSteps().mapIndexed { i, step ->
        val rx = step.prescription
        SessionRow(
            blockId = step.key.blockId,
            orderIndex = step.key.orderIndex,
            exerciseId = rx.exerciseId,
            name = rx.exercise?.name ?: "Exercise",
            blockTitle = step.block.title,
            targetSummary = sessionTargetSummary(rx),
            setsDone = logged[step.key]?.size ?: 0,
            setsTotal = rx.sets ?: 1,
            isTimed = rx.isTimed,
            isCurrent = i == current,
        )
    }
}

/** "3 × 5–8 · 135 lb" / "3 × 30s" / "3 × 8 · body weight" — the row's one-line target. */
private fun sessionTargetSummary(rx: Prescription): String {
    val sets = rx.sets ?: 1
    if (rx.isTimed) return "$sets × ${rx.durationSeconds ?: 0}s"
    val reps = when {
        rx.repsMin != null && rx.repsMax != null && rx.repsMin != rx.repsMax -> "${rx.repsMin}–${rx.repsMax}"
        rx.repsMax != null -> "${rx.repsMax}"
        rx.repsMin != null -> "${rx.repsMin}"
        else -> "—"
    }
    val load = when {
        rx.isBodyweight -> " · body weight"
        // Per-hand (dumbbell/dual-cable) loads read "55 lb/hand" so the number
        // isn't mistaken for a light total; everything else is plain "lb".
        rx.targetWeightLbs != null -> " · ${formatLbs(rx.targetWeightLbs)} ${weightUnitLabel(rx)}"
        else -> ""
    }
    return "$sets × $reps$load"
}

private fun formatLbs(w: Double): String =
    if (w == w.toLong().toDouble()) w.toLong().toString() else ((w * 10).toLong() / 10.0).toString()

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
                    // Within-session carry stays literal (you just did those reps).
                    // Only the CROSS-session fallback is floored to the band: a
                    // fresh/refined program whose rep band jumped up must not prefill
                    // below its own floor and render the suggestion red pre-lift.
                    else -> carried ?: run {
                        val fallback = lastTime?.reps ?: prescription.repsMax ?: prescription.repsMin
                        val floor = prescription.repsMin
                        if (fallback != null && floor != null) maxOf(fallback, floor) else fallback
                    }
                }
            },
        )
    }
}

/**
 * True when a prescription's load is logged PER HAND (dumbbell / dual-cable),
 * so the coaching screen labels the number "lb/hand" rather than letting a
 * per-hand load read as a light total ("55 lb/hand" ≈ 110 total). Name-based
 * fallback mirroring the backend LoadConventionResolver: single-implement
 * movements (goblet, single dumbbell, dumbbell pullover) are NOT per-hand.
 * Display-only — the logged number is unchanged.
 */
fun isPerHandLoad(p: Prescription): Boolean {
    val n = p.exercise?.name?.lowercase() ?: return false
    val single = listOf("goblet", "single dumbbell", "single-dumbbell", "one dumbbell", "dumbbell pullover")
    if (single.any { n.contains(it) }) return false
    val perHand = listOf("dumbbell", "db ", "dual cable", "dual-cable", "functional trainer", "cable crossover")
    return perHand.any { n.contains(it) }
}

/** The weight unit for a prescription: "lb/hand" for a per-hand load, else "lb". */
fun weightUnitLabel(p: Prescription): String = if (isPerHandLoad(p)) "lb/hand" else "lb"

