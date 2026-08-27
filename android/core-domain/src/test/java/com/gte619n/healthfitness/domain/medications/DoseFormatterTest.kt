package com.gte619n.healthfitness.domain.medications

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseFormatterTest {

    @Test
    fun `whole number renders without decimal`() {
        assertEquals("200 mg", DoseFormatter.format(200.0, "mg"))
    }

    @Test
    fun `fractional dose renders without trailing zeros`() {
        assertEquals("0.5 mg", DoseFormatter.format(0.5, "mg"))
    }

    @Test
    fun `two-decimal dose keeps both digits`() {
        assertEquals("0.25 mg", DoseFormatter.format(0.25, "mg"))
    }

    @Test
    fun `dose beyond two decimals rounds to two`() {
        assertEquals("0.13 mg", DoseFormatter.format(0.125, "mg"))
    }

    @Test
    fun `trailing-zero fraction trims to shortest form`() {
        assertEquals("12.5 mg", DoseFormatter.format(12.50, "mg"))
    }

    @Test
    fun `unit is preserved verbatim`() {
        assertEquals("1 IU", DoseFormatter.format(1.0, "IU"))
    }
}
