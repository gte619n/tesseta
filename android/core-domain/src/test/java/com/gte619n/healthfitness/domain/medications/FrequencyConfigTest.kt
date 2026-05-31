package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Pure tests for [DoseScheduleCalculator.dosesExpectedToday], mirroring what
 * the backend `/today` endpoint returns.
 */
class FrequencyConfigTest {

    private val twoSlots = listOf(
        TimeSlot(TimeWindow.MORNING, 1.0),
        TimeSlot(TimeWindow.EVENING, 1.0),
    )

    @Test
    fun `daily twice with two slots is two`() {
        val freq = FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 2)
        assertEquals(2, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, LocalDate.of(2026, 5, 27)))
    }

    @Test
    fun `daily uses timesPerPeriod when no slots`() {
        val freq = FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 2)
        assertEquals(2, DoseScheduleCalculator.dosesExpectedToday(freq, emptyList(), LocalDate.of(2026, 5, 27)))
    }

    @Test
    fun `prn is zero`() {
        val freq = FrequencyConfig(FrequencyType.PRN)
        assertEquals(0, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, LocalDate.of(2026, 5, 27)))
    }

    @Test
    fun `monthly is zero`() {
        val freq = FrequencyConfig(FrequencyType.MONTHLY)
        assertEquals(0, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, LocalDate.of(2026, 5, 27)))
    }

    @Test
    fun `weekly on mon-wed-fri matches slot count on wednesday`() {
        // 2026-05-27 is a Wednesday.
        val wednesday = LocalDate.of(2026, 5, 27)
        assertEquals(java.time.DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
        val freq = FrequencyConfig(
            type = FrequencyType.WEEKLY,
            timesPerPeriod = 3,
            specificDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
        )
        assertEquals(2, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, wednesday))
    }

    @Test
    fun `weekly on mon-wed-fri is zero on tuesday`() {
        // 2026-05-26 is a Tuesday.
        val tuesday = LocalDate.of(2026, 5, 26)
        assertEquals(java.time.DayOfWeek.TUESDAY, tuesday.dayOfWeek)
        val freq = FrequencyConfig(
            type = FrequencyType.WEEKLY,
            timesPerPeriod = 3,
            specificDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
        )
        assertEquals(0, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, tuesday))
    }

    @Test
    fun `weekly without specific days is zero`() {
        val freq = FrequencyConfig(FrequencyType.WEEKLY, timesPerPeriod = 1)
        assertEquals(0, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, LocalDate.of(2026, 5, 27)))
    }

    @Test
    fun `cycle inside off week is zero`() {
        // 8w on / 2w off starting 2026-01-01; week 9 (off) contains 2026-03-05.
        val freq = FrequencyConfig(
            type = FrequencyType.CYCLE,
            cycle = FrequencyConfig.CycleConfig(onWeeks = 8, offWeeks = 2, startDate = LocalDate.of(2026, 1, 1)),
        )
        // 2026-03-05 is 9 weeks after Jan 1 → in the off window (weeks 8-9, zero-indexed).
        assertEquals(0, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, LocalDate.of(2026, 3, 5)))
    }

    @Test
    fun `cycle inside on week matches slot count`() {
        val freq = FrequencyConfig(
            type = FrequencyType.CYCLE,
            cycle = FrequencyConfig.CycleConfig(onWeeks = 8, offWeeks = 2, startDate = LocalDate.of(2026, 1, 1)),
        )
        // 2026-01-15 is week 2 → on.
        assertEquals(2, DoseScheduleCalculator.dosesExpectedToday(freq, twoSlots, LocalDate.of(2026, 1, 15)))
    }
}
