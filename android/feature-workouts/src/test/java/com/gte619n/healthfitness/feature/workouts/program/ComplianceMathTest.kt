package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComplianceMathTest {

    private val today = LocalDate.parse("2026-06-10")

    private fun sched(date: String, status: ScheduledStatus): ScheduledWorkout =
        ScheduledWorkout(
            scheduledId = "s-$date",
            date = LocalDate.parse(date),
            phaseId = "ph",
            dayId = "d",
            dayLabel = "Day",
            weekIndexInPhase = 1,
            isDeload = false,
            locationId = "g",
            locationName = null,
            status = status,
        )

    private fun program(id: String, status: ProgramStatus, updatedAt: String): WorkoutProgram =
        ProgramFixtures.deepProgram.copy(
            programId = id,
            status = status,
            updatedAt = Instant.parse(updatedAt),
            phases = emptyList(),
        )

    // ---- resolveActiveProgram ----

    @Test
    fun `resolveActiveProgram prefers the ACTIVE program`() {
        val programs = listOf(
            program("draft", ProgramStatus.DRAFT, "2026-06-09T00:00:00Z"),
            program("active", ProgramStatus.ACTIVE, "2026-01-01T00:00:00Z"),
        )
        assertEquals("active", resolveActiveProgram(programs)?.programId)
    }

    @Test
    fun `resolveActiveProgram falls back to the most recently updated`() {
        val programs = listOf(
            program("old", ProgramStatus.COMPLETED, "2026-01-01T00:00:00Z"),
            program("new", ProgramStatus.DRAFT, "2026-06-09T00:00:00Z"),
        )
        assertEquals("new", resolveActiveProgram(programs)?.programId)
    }

    @Test
    fun `resolveActiveProgram returns null when there are no programs`() {
        assertNull(resolveActiveProgram(emptyList()))
    }

    // ---- computeWeeklyStreak ----
    //
    // today = 2026-06-10 (Wednesday). Monday-start weeks:
    //   current week : 2026-06-08 .. 06-14 (today is Wed 06-10)
    //   week -1      : 2026-06-01 .. 06-07
    //   week -2      : 2026-05-25 .. 05-31

    @Test
    fun `computeWeeklyStreak counts consecutive weeks meeting the target`() {
        val scheduled = listOf(
            // current week: 3 completed
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-09", ScheduledStatus.COMPLETED),
            sched("2026-06-10", ScheduledStatus.COMPLETED),
            // week -1: 3 completed
            sched("2026-06-01", ScheduledStatus.COMPLETED),
            sched("2026-06-02", ScheduledStatus.COMPLETED),
            sched("2026-06-03", ScheduledStatus.COMPLETED),
            // week -2: 3 completed
            sched("2026-05-25", ScheduledStatus.COMPLETED),
            sched("2026-05-26", ScheduledStatus.COMPLETED),
            sched("2026-05-27", ScheduledStatus.COMPLETED),
        )
        assertEquals(3, computeWeeklyStreak(scheduled, today, weeklyTarget = 3))
    }

    @Test
    fun `computeWeeklyStreak does not break on an in-progress current week below target`() {
        val scheduled = listOf(
            // current week: only 1 so far — still in progress, mustn't break
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            // week -1: 3 completed
            sched("2026-06-01", ScheduledStatus.COMPLETED),
            sched("2026-06-02", ScheduledStatus.COMPLETED),
            sched("2026-06-03", ScheduledStatus.COMPLETED),
            // week -2: 3 completed
            sched("2026-05-25", ScheduledStatus.COMPLETED),
            sched("2026-05-26", ScheduledStatus.COMPLETED),
            sched("2026-05-27", ScheduledStatus.COMPLETED),
        )
        // Current week is skipped (not yet at target) but the two prior weeks count.
        assertEquals(2, computeWeeklyStreak(scheduled, today, weeklyTarget = 3))
    }

    @Test
    fun `computeWeeklyStreak counts the current week once it meets the target`() {
        val scheduled = listOf(
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-09", ScheduledStatus.COMPLETED),
            sched("2026-06-01", ScheduledStatus.COMPLETED),
            sched("2026-06-02", ScheduledStatus.COMPLETED),
        )
        assertEquals(2, computeWeeklyStreak(scheduled, today, weeklyTarget = 2))
    }

    @Test
    fun `computeWeeklyStreak ends at the first past week below target`() {
        val scheduled = listOf(
            // current week meets target 3
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-09", ScheduledStatus.COMPLETED),
            sched("2026-06-10", ScheduledStatus.COMPLETED),
            // week -1: only 2 — falls short, ends the streak
            sched("2026-06-01", ScheduledStatus.COMPLETED),
            sched("2026-06-02", ScheduledStatus.COMPLETED),
            // week -2 would qualify but is unreachable past the broken week
            sched("2026-05-25", ScheduledStatus.COMPLETED),
            sched("2026-05-26", ScheduledStatus.COMPLETED),
            sched("2026-05-27", ScheduledStatus.COMPLETED),
        )
        assertEquals(1, computeWeeklyStreak(scheduled, today, weeklyTarget = 3))
    }

    @Test
    fun `computeWeeklyStreak breaks on a week with no completed workouts`() {
        val scheduled = listOf(
            // current week meets target 2
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-09", ScheduledStatus.COMPLETED),
            // week -1: nothing completed (a whole week missed)
            sched("2026-06-03", ScheduledStatus.SKIPPED),
            // week -2 qualifies but is unreachable
            sched("2026-05-25", ScheduledStatus.COMPLETED),
            sched("2026-05-26", ScheduledStatus.COMPLETED),
        )
        assertEquals(1, computeWeeklyStreak(scheduled, today, weeklyTarget = 2))
    }

    @Test
    fun `computeWeeklyStreak is zero when no week meets the target`() {
        val scheduled = listOf(
            sched("2026-06-08", ScheduledStatus.COMPLETED),
        )
        assertEquals(0, computeWeeklyStreak(scheduled, today, weeklyTarget = 3))
    }

    @Test
    fun `computeWeeklyStreak is zero for a non-positive target`() {
        val scheduled = listOf(
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-09", ScheduledStatus.COMPLETED),
        )
        assertEquals(0, computeWeeklyStreak(scheduled, today, weeklyTarget = 0))
    }

    @Test
    fun `computeWeeklyStreak is zero for an empty calendar`() {
        assertEquals(0, computeWeeklyStreak(emptyList(), today, weeklyTarget = 3))
    }

    @Test
    fun `computeWeeklyStreak does not let duplicate dates inflate a week to target`() {
        // Same date logged twice must count once, so this week has 2 distinct
        // completed days — below the target of 3 — and contributes no streak.
        val scheduled = listOf(
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-09", ScheduledStatus.COMPLETED),
        )
        assertEquals(0, computeWeeklyStreak(scheduled, today, weeklyTarget = 3))
    }

    // ---- completedThisWeek ----

    @Test
    fun `completedThisWeek counts only this week's completed workouts`() {
        val scheduled = listOf(
            sched("2026-06-08", ScheduledStatus.COMPLETED), // this week
            sched("2026-06-09", ScheduledStatus.COMPLETED), // this week
            sched("2026-06-10", ScheduledStatus.PLANNED), // today, not completed
            sched("2026-06-01", ScheduledStatus.COMPLETED), // last week — excluded
        )
        assertEquals(2, completedThisWeek(scheduled, today))
    }

    // ---- performed-date semantics: count on the day actually done ----

    private val utc = java.time.ZoneId.of("UTC")

    /** A COMPLETED session scheduled on one day but performed (completedAt) on another. */
    private fun done(scheduledDate: String, performedInstant: String): ScheduledWorkout =
        sched(scheduledDate, ScheduledStatus.COMPLETED)
            .copy(completedAt = Instant.parse(performedInstant))

    @Test
    fun `completedThisWeek counts a late-completed session in the week it was performed`() {
        // Scheduled last Sunday (2026-06-07) but performed Monday this week (06-08):
        // it belongs to THIS week, not last.
        val scheduled = listOf(done("2026-06-07", "2026-06-08T12:00:00Z"))
        assertEquals(1, completedThisWeek(scheduled, today, utc))
    }

    @Test
    fun `computeWeeklyStreak groups by performed date across a week boundary`() {
        // A single session scheduled last Sunday but performed this Monday counts for
        // the current week only — so with target 1 the streak is the current week (1).
        val scheduled = listOf(done("2026-06-07", "2026-06-08T12:00:00Z"))
        assertEquals(1, computeWeeklyStreak(scheduled, today, weeklyTarget = 1, zone = utc))
    }

    // ---- complianceGrid ----

    @Test
    fun `complianceGrid places a late-completed session on the day performed and misses the planned slot`() {
        // Scheduled Monday 06-08, actually performed Tuesday 06-09.
        val grid = complianceGrid(
            listOf(done("2026-06-08", "2026-06-09T12:00:00Z")),
            today,
            utc,
        )
        assertEquals(ComplianceCellKind.MISSED, grid[LocalDate.parse("2026-06-08")])
        assertEquals(ComplianceCellKind.COMPLETED, grid[LocalDate.parse("2026-06-09")])
    }

    @Test
    fun `complianceGrid marks future planned days upcoming and rest days absent`() {
        val grid = complianceGrid(
            listOf(sched("2026-06-15", ScheduledStatus.PLANNED)),
            today,
            utc,
        )
        assertEquals(ComplianceCellKind.UPCOMING, grid[LocalDate.parse("2026-06-15")])
        assertNull(grid[LocalDate.parse("2026-06-14")]) // no session ⇒ rest (absent)
    }

    // ---- cellKind ----

    @Test
    fun `cellKind maps a non-scheduled day to REST`() {
        assertEquals(ComplianceCellKind.REST, cellKind(LocalDate.parse("2026-06-04"), null, today))
    }

    @Test
    fun `cellKind maps a completed day to COMPLETED`() {
        assertEquals(
            ComplianceCellKind.COMPLETED,
            cellKind(LocalDate.parse("2026-06-04"), ScheduledStatus.COMPLETED, today),
        )
    }

    @Test
    fun `cellKind maps a past planned or skipped day to MISSED`() {
        assertEquals(
            ComplianceCellKind.MISSED,
            cellKind(LocalDate.parse("2026-06-04"), ScheduledStatus.PLANNED, today),
        )
        assertEquals(
            ComplianceCellKind.MISSED,
            cellKind(LocalDate.parse("2026-06-04"), ScheduledStatus.SKIPPED, today),
        )
    }

    @Test
    fun `cellKind maps today and future scheduled days to UPCOMING`() {
        assertEquals(
            ComplianceCellKind.UPCOMING,
            cellKind(today, ScheduledStatus.PLANNED, today),
        )
        assertEquals(
            ComplianceCellKind.UPCOMING,
            cellKind(LocalDate.parse("2026-06-15"), ScheduledStatus.PLANNED, today),
        )
    }
}
