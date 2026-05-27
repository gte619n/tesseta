package com.gte619n.healthfitness.domain.medications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FrequencyFormatterTest {

    @Test fun `daily once`() {
        assertEquals(
            "Once daily",
            FrequencyFormatter.format(FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 1)),
        )
    }

    @Test fun `daily twice`() {
        assertEquals(
            "2x daily",
            FrequencyFormatter.format(FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 2)),
        )
    }

    @Test fun `weekly thrice`() {
        assertEquals(
            "3x weekly",
            FrequencyFormatter.format(FrequencyConfig(FrequencyType.WEEKLY, timesPerPeriod = 3)),
        )
    }

    @Test fun `monthly`() {
        assertEquals(
            "Monthly",
            FrequencyFormatter.format(FrequencyConfig(FrequencyType.MONTHLY)),
        )
    }

    @Test fun `prn`() {
        assertEquals(
            "As needed",
            FrequencyFormatter.format(FrequencyConfig(FrequencyType.PRN)),
        )
    }

    @Test fun `cycle with weeks`() {
        val cfg = FrequencyConfig(
            FrequencyType.CYCLE,
            cycle = FrequencyConfig.CycleConfig(onWeeks = 4, offWeeks = 2, startDate = LocalDate.of(2026, 1, 1)),
        )
        assertEquals("4w on / 2w off", FrequencyFormatter.format(cfg))
    }

    @Test fun `cycle without config falls back to label`() {
        assertEquals("Cycle", FrequencyFormatter.format(FrequencyConfig(FrequencyType.CYCLE)))
    }
}
