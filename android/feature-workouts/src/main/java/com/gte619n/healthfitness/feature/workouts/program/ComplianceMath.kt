package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

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

/** The Monday that starts the (Monday–Sunday) week containing [date]. */
fun weekStartOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * Completed workouts logged so far in the week that contains [today] (Monday
 * through today inclusive). Drives the "N of TARGET this week" progress hint and
 * feeds [computeWeeklyStreak]'s current-week check.
 */
fun completedThisWeek(scheduled: List<ScheduledWorkout>, today: LocalDate): Int {
    val weekStart = weekStartOf(today)
    return scheduled
        .filter { it.status == ScheduledStatus.COMPLETED && it.date in weekStart..today }
        .distinctBy { it.date }
        .size
}

/**
 * The current streak measured in *consecutive weeks* that each met the
 * [weeklyTarget] number of completed workouts, walking backward from the week
 * containing [today].
 *
 * The in-progress current week never *breaks* the streak: if it has already hit
 * the target it counts (+1), otherwise it is simply skipped — the user still has
 * days left in the week to reach the target — and the run is measured from the
 * prior weeks. Each fully-elapsed earlier week must meet the target; the first
 * one that falls short ends the streak. A week with no completed workouts (a
 * skipped or pre-program week) therefore breaks it. Returns 0 for a non-positive
 * target.
 */
fun computeWeeklyStreak(
    scheduled: List<ScheduledWorkout>,
    today: LocalDate,
    weeklyTarget: Int,
): Int {
    if (weeklyTarget < 1) return 0
    // Completed workouts per Monday-start week. One outcome per date (defensive
    // against duplicate rows from re-materialization); a date can only be
    // completed once, so distinctBy(date) is safe here.
    val completedByWeek: Map<LocalDate, Int> = scheduled
        .filter { it.status == ScheduledStatus.COMPLETED && it.date <= today }
        .distinctBy { it.date }
        .groupingBy { weekStartOf(it.date) }
        .eachCount()

    val currentWeek = weekStartOf(today)
    var streak = 0
    if ((completedByWeek[currentWeek] ?: 0) >= weeklyTarget) {
        streak++
    }
    // Walk back through fully-elapsed weeks; each must meet the target.
    var week = currentWeek.minusWeeks(1)
    while ((completedByWeek[week] ?: 0) >= weeklyTarget) {
        streak++
        week = week.minusWeeks(1)
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
