package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import java.time.LocalDate

/**
 * Pure compliance/streak helpers for the "This Week" landing. The backend has no
 * compliance or streak endpoint, so both are derived client-side from the
 * authoritative [ScheduledWorkout.status] + [ScheduledWorkout.date] calendar
 * rows. Kept UI-free so they can be unit-tested in isolation (ComplianceMathTest).
 */

/** How a single calendar day renders on the compliance grid. */
enum class ComplianceCellKind {
    /** A scheduled training day that was completed. */
    COMPLETED,

    /** A scheduled training day in the past that was not completed (planned/skipped). */
    MISSED,

    /** A scheduled training day today or in the future — not yet due. */
    UPCOMING,

    /** Not a scheduled training day. */
    REST,
}

/**
 * The program the landing should feature: the ACTIVE one if present, else the
 * most recently touched program (so a user with only drafts/completed still sees
 * something). Null only when there are no programs at all.
 */
fun resolveActiveProgram(programs: List<WorkoutProgram>): WorkoutProgram? =
    programs.firstOrNull { it.status == ProgramStatus.ACTIVE }
        ?: programs.maxByOrNull { it.updatedAt }

/**
 * The current in-compliance streak: consecutive scheduled training days, walking
 * backward from the most recent non-future scheduled day, that are COMPLETED.
 * Stops at the first past PLANNED/SKIPPED day. Future days are ignored entirely,
 * and non-scheduled (rest) days neither extend nor break the run.
 */
fun computeStreak(scheduled: List<ScheduledWorkout>, today: LocalDate): Int {
    val days = scheduled
        .filter { it.date <= today }
        .sortedByDescending { it.date }
        // One outcome per date: the most recent row for a date wins (defensive —
        // a program's calendar is one session per date, but re-materialization
        // could momentarily surface duplicates).
        .distinctBy { it.date }
    var streak = 0
    for (day in days) {
        if (day.status == ScheduledStatus.COMPLETED) streak++ else break
    }
    return streak
}

/**
 * The compliance-grid classification for a single day. [status] is null for a
 * non-scheduled (rest) day. A scheduled day today counts as [UPCOMING] — it is
 * still due — so the boundary is `date < today` for MISSED.
 */
fun cellKind(date: LocalDate, status: ScheduledStatus?, today: LocalDate): ComplianceCellKind =
    when (status) {
        null -> ComplianceCellKind.REST
        ScheduledStatus.COMPLETED -> ComplianceCellKind.COMPLETED
        ScheduledStatus.PLANNED, ScheduledStatus.SKIPPED ->
            if (date < today) ComplianceCellKind.MISSED else ComplianceCellKind.UPCOMING
    }
