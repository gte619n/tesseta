package com.gte619n.healthfitness.domain.medications

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseFormatterTest {

    @Test
    fun `whole number renders without decimal`() {
        assertEquals("200 mg", DoseFormatter.format(200.0, "mg"))
    }

    @Test
    fun `fractional dose renders one decimal`() {
        assertEquals("0.5 mg", DoseFormatter.format(0.5, "mg"))
    }

    @Test
    fun `trailing-zero fraction rounds to one decimal`() {
        assertEquals("12.5 mg", DoseFormatter.format(12.50, "mg"))
    }

    @Test
    fun `unit is preserved verbatim`() {
        assertEquals("1 IU", DoseFormatter.format(1.0, "IU"))
    }
}
