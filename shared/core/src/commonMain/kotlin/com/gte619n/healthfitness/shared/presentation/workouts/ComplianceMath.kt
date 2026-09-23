package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutProgram
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * IMPL-IOS-01 Phase 3 Wave D — KMP port of the Android
 * `feature-workouts/.../program/ComplianceMath.kt`. Pure compliance/streak
 * helpers for the "This Week" landing. The backend has no compliance or streak
 * endpoint, so both are derived client-side from the authoritative
 * [ScheduledWorkout.status] + [ScheduledWorkout.date] calendar rows. Anti-drift:
 * this single-sourced logic is shared, not reimplemented per platform.
 *
 * `java.time` → kotlinx-datetime; the Monday-start week math is expressed with
 * `LocalDate.dayOfWeek` (kotlinx `DayOfWeek`, Monday = 1).
 */

/** How a single calendar day renders on the compliance grid. */
enum class ComplianceCellKind { COMPLETED, MISSED, UPCOMING, REST }

/** Weekly-streak defaults (was `WorkoutStreakSettings` on Android). */
object WorkoutStreakSettings {
    const val DEFAULT_WEEKLY_TARGET: Int = 3
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
fun weekStartOf(date: LocalDate): LocalDate {
    val daysFromMonday = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> 0
        DayOfWeek.TUESDAY -> 1
        DayOfWeek.WEDNESDAY -> 2
        DayOfWeek.THURSDAY -> 3
        DayOfWeek.FRIDAY -> 4
        DayOfWeek.SATURDAY -> 5
        DayOfWeek.SUNDAY -> 6
        else -> 0
    }
    return date.minus(daysFromMonday, DateTimeUnit.DAY)
}

/**
 * Completed workouts logged so far in the week that contains [today] (Monday
 * through today inclusive). Drives the "N of TARGET this week" hint and feeds
 * [computeWeeklyStreak]'s current-week check.
 */
fun completedThisWeek(scheduled: List<ScheduledWorkout>, today: LocalDate): Int {
    val weekStart = weekStartOf(today)
    return scheduled
        .filter { it.status == ScheduledStatus.COMPLETED && it.date >= weekStart && it.date <= today }
        .distinctBy { it.date }
        .size
}

/**
 * The current streak measured in *consecutive weeks* that each met the
 * [weeklyTarget] number of completed workouts, walking backward from the week
 * containing [today].
 *
 * The in-progress current week never *breaks* the streak: if it has already hit
 * the target it counts (+1), otherwise it is simply skipped and the run is
 * measured from the prior weeks. Each fully-elapsed earlier week must meet the
 * target; the first that falls short ends the streak. Returns 0 for a
 * non-positive target.
 */
fun computeWeeklyStreak(
    scheduled: List<ScheduledWorkout>,
    today: LocalDate,
    weeklyTarget: Int,
): Int {
    if (weeklyTarget < 1) return 0
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
    var week = currentWeek.minus(1, DateTimeUnit.WEEK)
    while ((completedByWeek[week] ?: 0) >= weeklyTarget) {
        streak++
        week = week.minus(1, DateTimeUnit.WEEK)
    }
    return streak
}

/**
 * The compliance-grid classification for a single day. [status] is null for a
 * non-scheduled (rest) day. A scheduled day today counts as [UPCOMING] — still
 * due — so the boundary is `date < today` for MISSED.
 */
fun cellKind(date: LocalDate, status: ScheduledStatus?, today: LocalDate): ComplianceCellKind =
    when (status) {
        null -> ComplianceCellKind.REST
        ScheduledStatus.COMPLETED -> ComplianceCellKind.COMPLETED
        ScheduledStatus.PLANNED, ScheduledStatus.SKIPPED ->
            if (date < today) ComplianceCellKind.MISSED else ComplianceCellKind.UPCOMING
    }

/** The 7 days (Mon–Sun) of the week containing [date], for a compliance strip. */
fun weekDays(date: LocalDate): List<LocalDate> {
    val start = weekStartOf(date)
    return (0..6).map { start.plus(it, DateTimeUnit.DAY) }
}

/**
 * A calendar month (year + 1..12 month). kotlinx-datetime has no `YearMonth`, so
 * this small value stands in for the compliance-grid's visible-month navigation
 * (the Android landing used `java.time.YearMonth`).
 */
data class YearMonth(val year: Int, val month: Int) {
    /** First day of the month. */
    fun atDay(day: Int): LocalDate = LocalDate(year, month, day)

    fun atStartOfMonth(): LocalDate = atDay(1)

    /** Last day of the month (handles leap Februaries). */
    fun atEndOfMonth(): LocalDate =
        atDay(1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

    fun minusMonths(n: Int): YearMonth = of(atDay(1).minus(n, DateTimeUnit.MONTH))

    fun plusMonths(n: Int): YearMonth = of(atDay(1).plus(n, DateTimeUnit.MONTH))

    companion object {
        fun of(date: LocalDate): YearMonth = YearMonth(date.year, date.monthNumber)
    }
}

/** True when [date] falls within [month]. */
fun LocalDate.inMonth(month: YearMonth): Boolean =
    year == month.year && monthNumber == month.month
