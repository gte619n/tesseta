package com.gte619n.healthfitness.domain.medications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FrequencyFormatterTest {

    @Test
    fun `daily once`() {
        assertEquals("Once daily", FrequencyFormatter.format(FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 1)))
    }

    @Test
    fun `daily multiple`() {
        assertEquals("3x daily", FrequencyFormatter.format(FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 3)))
    }

    @Test
    fun `daily defaults to once when null`() {
        assertEquals("Once daily", FrequencyFormatter.format(FrequencyConfig(FrequencyType.DAILY)))
    }

    @Test
    fun `weekly`() {
        assertEquals("2x weekly", FrequencyFormatter.format(FrequencyConfig(FrequencyType.WEEKLY, timesPerPeriod = 2)))
    }

    @Test
    fun `monthly`() {
        assertEquals("Monthly", FrequencyFormatter.format(FrequencyConfig(FrequencyType.MONTHLY)))
    }

    @Test
    fun `prn`() {
        assertEquals("As needed", FrequencyFormatter.format(FrequencyConfig(FrequencyType.PRN)))
    }

    @Test
    fun `cycle with config`() {
        val cycle = FrequencyConfig(
            type = FrequencyType.CYCLE,
            cycle = FrequencyConfig.CycleConfig(onWeeks = 8, offWeeks = 2, startDate = LocalDate.of(2026, 1, 1)),
        )
        assertEquals("8w on / 2w off", FrequencyFormatter.format(cycle))
    }

    @Test
    fun `cycle without config falls back`() {
        assertEquals("Cycle", FrequencyFormatter.format(FrequencyConfig(FrequencyType.CYCLE)))
    }
}
