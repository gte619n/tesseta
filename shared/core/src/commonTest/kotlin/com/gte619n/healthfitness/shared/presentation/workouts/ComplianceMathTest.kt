package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ported-from-Android compliance/streak logic (#278 performed-date, #279/#280
 * cross-program heatmap). Pure → runs identically on JVM + iOS.
 */
class ComplianceMathTest {

    private val utc = TimeZone.UTC

    private fun row(
        date: String,
        status: ScheduledStatus,
        completedAt: String? = null,
        id: String = "s-$date",
    ) = ScheduledWorkout(
        scheduledId = id,
        date = LocalDate.parse(date),
        phaseId = "p", dayId = "d", dayLabel = "L",
        weekIndexInPhase = 0, isDeload = false,
        locationId = "", locationName = null,
        status = status,
        completedAt = completedAt?.let { Instant.parse(it) },
    )

    @Test
    fun performedDateUsesCompletionDayNotPlannedSlot() {
        // Planned Thursday 2026-09-24, actually completed Friday (UTC).
        val late = row("2026-09-24", ScheduledStatus.COMPLETED, completedAt = "2026-09-25T02:00:00Z")
        assertEquals(LocalDate.parse("2026-09-25"), performedDate(late, utc))
    }

    @Test
    fun performedDateFallsBackToScheduledDateWhenNoCompletion() {
        val planned = row("2026-09-24", ScheduledStatus.PLANNED)
        assertEquals(LocalDate.parse("2026-09-24"), performedDate(planned, utc))
    }

    @Test
    fun completedThisWeekCountsByPerformedDay() {
        val today = LocalDate.parse("2026-09-25") // Friday
        // Completed Mon + a Thu-planned/Fri-performed → both land in this week (Mon-start).
        val rows = listOf(
            row("2026-09-21", ScheduledStatus.COMPLETED, completedAt = "2026-09-21T12:00:00Z"),
            row("2026-09-24", ScheduledStatus.COMPLETED, completedAt = "2026-09-25T02:00:00Z"),
        )
        assertEquals(2, completedThisWeek(rows, today, utc))
    }

    @Test
    fun complianceGridMarksPerformedDayCompletedAndPlannedSlotMissed() {
        val today = LocalDate.parse("2026-09-26")
        val late = row("2026-09-24", ScheduledStatus.COMPLETED, completedAt = "2026-09-25T02:00:00Z")
        val grid = complianceGrid(listOf(late), today, zone = utc)
        // The day it was performed lights up COMPLETED...
        assertEquals(ComplianceCellKind.COMPLETED, grid[LocalDate.parse("2026-09-25")])
        // ...and the planned slot it skipped shows MISSED (past).
        assertEquals(ComplianceCellKind.MISSED, grid[LocalDate.parse("2026-09-24")])
    }

    @Test
    fun crossProgramHeatmapDaysLightUpCompleted() {
        val today = LocalDate.parse("2026-09-26")
        val extra = setOf(LocalDate.parse("2026-08-14")) // an archived program's month
        val grid = complianceGrid(emptyList(), today, extraCompletedDates = extra, zone = utc)
        assertEquals(ComplianceCellKind.COMPLETED, grid[LocalDate.parse("2026-08-14")])
    }
}
