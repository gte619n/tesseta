package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * IMPL-21: the reminder due-date rule ([ReminderPlanner.isDueOn]) — the single source
 * of "does this medication have doses on this date?" shared by [OutstandingDoses]. The
 * old per-window `plan()` grouping tests moved to [OutstandingDosesTest] when the
 * multi-notification engine was removed (decision D-6).
 */
class ReminderPlannerTest {

    private val monday = LocalDate.of(2026, 6, 8)

    @Test
    fun daily_isAlwaysDue_prn_never() {
        assertTrue(ReminderPlanner.isDueOn(med("m1", "Metformin"), monday))
        val prn = med("m2", "Ibuprofen", frequency = FrequencyConfig(FrequencyType.PRN))
        assertFalse(ReminderPlanner.isDueOn(prn, monday))
    }

    @Test
    fun weekly_firesOnlyOnItsDays() {
        val mwf = med(
            "m1", "Alendronate",
            frequency = FrequencyConfig(
                FrequencyType.WEEKLY,
                specificDays = listOf(DayOfWeek.MON, DayOfWeek.FRI),
            ),
        )
        assertTrue(ReminderPlanner.isDueOn(mwf, monday))
        assertFalse(ReminderPlanner.isDueOn(mwf, monday.plusDays(1)))
        assertTrue(ReminderPlanner.isDueOn(mwf, monday.plusDays(4)))
    }

    @Test
    fun cycle_respectsOnOffWeeks() {
        val cycled = med(
            "m1", "Peptide",
            frequency = FrequencyConfig(
                FrequencyType.CYCLE,
                cycle = FrequencyConfig.CycleConfig(
                    onWeeks = 2, offWeeks = 1, startDate = monday.minusWeeks(2)),
            ),
        )
        // Weeks 0-1 on, week 2 off, weeks 3-4 on…
        assertFalse(ReminderPlanner.isDueOn(cycled, monday))
        assertTrue(ReminderPlanner.isDueOn(cycled, monday.plusWeeks(1)))
    }

    @Test
    fun discontinued_andNotYetStarted_areExcluded() {
        val stopped = med("m1", "Old", status = MedicationStatus.DISCONTINUED)
        assertFalse(ReminderPlanner.isDueOn(stopped, monday))

        val future = med("m2", "New", startDate = monday.plusDays(3))
        assertFalse(ReminderPlanner.isDueOn(future, monday))
        assertTrue(ReminderPlanner.isDueOn(future, monday.plusDays(3)))
    }

    // ---- fixture -------------------------------------------------------------

    private fun med(
        id: String,
        name: String,
        frequency: FrequencyConfig = FrequencyConfig(FrequencyType.DAILY),
        status: MedicationStatus = MedicationStatus.ACTIVE,
        startDate: LocalDate = LocalDate.of(2026, 1, 1),
    ): Medication = Medication(
        medicationId = id,
        drugId = null,
        drug = null,
        customName = name,
        status = status,
        dose = 1.0,
        unit = "mg",
        frequency = frequency,
        timeSlots = listOf(TimeSlot(TimeWindow.MORNING, 1.0)),
        protocolId = null,
        notes = null,
        prescribedBy = null,
        startDate = startDate,
        endDate = null,
        discontinueReason = null,
        discontinueNotes = null,
        correlatedMarkers = emptyList(),
        adherence = null,
    )
}
