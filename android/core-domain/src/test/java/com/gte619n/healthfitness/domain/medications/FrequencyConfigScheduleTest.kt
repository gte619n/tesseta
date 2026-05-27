package com.gte619n.healthfitness.domain.medications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FrequencyConfigScheduleTest {

    private val today = LocalDate.of(2026, 5, 27)   // Wednesday

    @Test fun `DAILY twice a day with two slots returns 2`() {
        val cfg = FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 2)
        val slots = listOf(
            TimeSlot(TimeWindow.MORNING, 100.0),
            TimeSlot(TimeWindow.EVENING, 100.0),
        )
        assertEquals(2, FrequencyConfigSchedule.dosesToday(cfg, slots, today))
    }

    @Test fun `PRN is never scheduled`() {
        val cfg = FrequencyConfig(FrequencyType.PRN)
        val slots = listOf(TimeSlot(TimeWindow.MORNING, 50.0))
        assertEquals(0, FrequencyConfigSchedule.dosesToday(cfg, slots, today))
        assertFalse(FrequencyConfigSchedule.isScheduledToday(cfg, today))
    }

    @Test fun `WEEKLY MWF on a Wednesday matches slot count`() {
        val cfg = FrequencyConfig(
            FrequencyType.WEEKLY,
            timesPerPeriod = 3,
            specificDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
        )
        val slots = listOf(TimeSlot(TimeWindow.MORNING, 0.5))
        assertEquals(1, FrequencyConfigSchedule.dosesToday(cfg, slots, today))
    }

    @Test fun `WEEKLY MWF on a Tuesday returns 0`() {
        val tuesday = LocalDate.of(2026, 5, 26)
        val cfg = FrequencyConfig(
            FrequencyType.WEEKLY,
            timesPerPeriod = 3,
            specificDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
        )
        val slots = listOf(TimeSlot(TimeWindow.MORNING, 0.5))
        assertEquals(0, FrequencyConfigSchedule.dosesToday(cfg, slots, tuesday))
    }

    @Test fun `WEEKLY without specificDays is scheduled every day`() {
        val cfg = FrequencyConfig(FrequencyType.WEEKLY, timesPerPeriod = 1)
        assertTrue(FrequencyConfigSchedule.isScheduledToday(cfg, today))
    }

    @Test fun `CYCLE inside off-week returns 0`() {
        // 4 weeks on, 2 weeks off; start 2026-01-01 (Thu).
        // Day-in-cycle 30 days after start = 30 % 42 = 30 → which is day 30, inside off-week (28..41).
        val startDate = LocalDate.of(2026, 1, 1)
        val cfg = FrequencyConfig(
            FrequencyType.CYCLE,
            cycle = FrequencyConfig.CycleConfig(onWeeks = 4, offWeeks = 2, startDate = startDate),
        )
        val insideOffWeek = startDate.plusDays(30)
        val slots = listOf(TimeSlot(TimeWindow.MORNING, 200.0))
        assertEquals(0, FrequencyConfigSchedule.dosesToday(cfg, slots, insideOffWeek))
    }

    @Test fun `CYCLE inside on-week returns slot count`() {
        val startDate = LocalDate.of(2026, 1, 1)
        val cfg = FrequencyConfig(
            FrequencyType.CYCLE,
            cycle = FrequencyConfig.CycleConfig(onWeeks = 4, offWeeks = 2, startDate = startDate),
        )
        val insideOnWeek = startDate.plusDays(3)
        val slots = listOf(TimeSlot(TimeWindow.MORNING, 200.0))
        assertEquals(1, FrequencyConfigSchedule.dosesToday(cfg, slots, insideOnWeek))
    }

    @Test fun `empty time slots default to one dose when scheduled`() {
        val cfg = FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 1)
        assertEquals(1, FrequencyConfigSchedule.dosesToday(cfg, emptyList(), today))
    }
}
