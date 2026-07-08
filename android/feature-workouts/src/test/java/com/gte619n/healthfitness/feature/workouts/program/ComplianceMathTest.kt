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

    // ---- computeStreak ----

    @Test
    fun `computeStreak counts a run of completed days back from the latest`() {
        val scheduled = listOf(
            sched("2026-06-01", ScheduledStatus.COMPLETED),
            sched("2026-06-03", ScheduledStatus.COMPLETED),
            sched("2026-06-05", ScheduledStatus.COMPLETED),
        )
        assertEquals(3, computeStreak(scheduled, today))
    }

    @Test
    fun `computeStreak breaks on a past planned day`() {
        val scheduled = listOf(
            sched("2026-06-01", ScheduledStatus.COMPLETED),
            sched("2026-06-03", ScheduledStatus.PLANNED), // missed
            sched("2026-06-05", ScheduledStatus.COMPLETED),
        )
        // Only the latest completed day counts; the earlier planned day stops it.
        assertEquals(1, computeStreak(scheduled, today))
    }

    @Test
    fun `computeStreak breaks on a past skipped day`() {
        val scheduled = listOf(
            sched("2026-06-05", ScheduledStatus.COMPLETED),
            sched("2026-06-03", ScheduledStatus.SKIPPED),
        )
        assertEquals(1, computeStreak(scheduled, today))
    }

    @Test
    fun `computeStreak ignores future scheduled days`() {
        val scheduled = listOf(
            sched("2026-06-05", ScheduledStatus.COMPLETED),
            sched("2026-06-12", ScheduledStatus.PLANNED), // future — ignored
            sched("2026-06-15", ScheduledStatus.PLANNED),
        )
        assertEquals(1, computeStreak(scheduled, today))
    }

    @Test
    fun `computeStreak is zero when the latest past day is not completed`() {
        val scheduled = listOf(
            sched("2026-06-05", ScheduledStatus.PLANNED),
        )
        assertEquals(0, computeStreak(scheduled, today))
    }

    @Test
    fun `computeStreak is zero for an empty calendar`() {
        assertEquals(0, computeStreak(emptyList(), today))
    }

    @Test
    fun `computeStreak collapses duplicate dates keeping the completed outcome`() {
        // Two rows for the same date; the completed one is the surviving outcome.
        val scheduled = listOf(
            sched("2026-06-05", ScheduledStatus.COMPLETED),
            sched("2026-06-05", ScheduledStatus.COMPLETED),
            sched("2026-06-03", ScheduledStatus.COMPLETED),
        )
        assertEquals(2, computeStreak(scheduled, today))
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
