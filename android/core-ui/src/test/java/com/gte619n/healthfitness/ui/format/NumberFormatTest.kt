package com.gte619n.healthfitness.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormatTest {

    @Test
    fun `groups thousands and drops the decimal for whole numbers`() {
        assertEquals("1,409", formatNumber(1409.0))
        assertEquals("409", formatNumber(409.0))
        assertEquals("1,234,567", formatNumber(1234567.0))
    }

    @Test
    fun `keeps up to maxDecimals for fractional values`() {
        assertEquals("1,409.5", formatNumber(1409.53))
        assertEquals("12.3", formatNumber(12.34))
        assertEquals("1,409.53", formatNumber(1409.531, maxDecimals = 2))
    }

    @Test
    fun `formatWholeNumber rounds and groups`() {
        assertEquals("2,450", formatWholeNumber(2450.4))
        assertEquals("1,000", formatWholeNumber(999.6))
        assertEquals("42", formatWholeNumber(42.0))
    }
}
