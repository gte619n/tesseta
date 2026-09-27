package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
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
 * The calendar day a session was actually performed: [ScheduledWorkout.completedAt]
 * resolved to [zone], falling back to the scheduled [ScheduledWorkout.date] when no
 * completion timestamp is present (still-planned rows, or imported history). Streak
 * and compliance count by this day, so a session done a day late (missed Thursday,
 * done Friday) lands on the day it actually happened — not its planned slot.
 */
fun performedDate(workout: ScheduledWorkout, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    workout.completedAt?.atZone(zone)?.toLocalDate() ?: workout.date

/**
 * Completed workouts logged so far in the week that contains [today] (Monday
 * through today inclusive), counted by the day each was actually performed. Drives
 * the "N of TARGET this week" progress hint and feeds [computeWeeklyStreak]'s
 * current-week check.
 */
fun completedThisWeek(
    scheduled: List<ScheduledWorkout>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    val weekStart = weekStartOf(today)
    return scheduled
        .filter { it.status == ScheduledStatus.COMPLETED }
        .map { performedDate(it, zone) }
        .filter { it in weekStart..today }
        .distinct()
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
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    if (weeklyTarget < 1) return 0
    // Completed workouts per Monday-start week, grouped by the day each was actually
    // performed. One outcome per performed date (defensive against duplicate rows
    // from re-materialization, and against two same-day sessions inflating a week).
    val completedByWeek: Map<LocalDate, Int> = scheduled
        .filter { it.status == ScheduledStatus.COMPLETED }
        .map { performedDate(it, zone) }
        .filter { it <= today }
        .distinct()
        .groupingBy { weekStartOf(it) }
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

/**
 * Classify every relevant day for the compliance grid, keyed by the day it maps
 * to on the calendar. A completed session lands on the day it was actually
 * performed ([performedDate]) — so catching up a missed day lights up the day you
 * did it. Every scheduled training slot that isn't itself a completed-performed
 * day is a MISSED (past) or UPCOMING (today/future) cell, which correctly leaves
 * the planned day of a late-completed session showing as missed. Days absent from
 * the map are rest days (the caller renders them plainly).
 */
fun complianceGrid(
    scheduled: List<ScheduledWorkout>,
    today: LocalDate,
    // Extra completed days the featured [scheduled] list can't know about — the
    // cross-program (incl. archived) heatmap days from the server. They light up
    // as COMPLETED, so a month owned entirely by an earlier program still shows
    // the user's workouts (parity with the web heatmap). Empty offline.
    extraCompletedDates: Set<LocalDate> = emptySet(),
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, ComplianceCellKind> {
    val kinds = mutableMapOf<LocalDate, ComplianceCellKind>()
    // Completed sessions first: mark the day they were performed.
    scheduled.filter { it.status == ScheduledStatus.COMPLETED }
        .forEach { kinds[performedDate(it, zone)] = ComplianceCellKind.COMPLETED }
    // Then every scheduled slot that isn't already a completed-performed day.
    scheduled.forEach { s ->
        if (kinds[s.date] == ComplianceCellKind.COMPLETED) return@forEach
        kinds[s.date] = if (s.date < today) ComplianceCellKind.MISSED else ComplianceCellKind.UPCOMING
    }
    // Cross-program completed days win over a MISSED slot (a session performed for
    // another program that day) but never override an already-COMPLETED cell.
    extraCompletedDates.forEach { kinds[it] = ComplianceCellKind.COMPLETED }
    return kinds
}
