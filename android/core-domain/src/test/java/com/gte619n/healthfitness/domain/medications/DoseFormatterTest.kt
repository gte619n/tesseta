package com.gte619n.healthfitness.domain.medications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Lock the integer/decimal heuristic so the medications card, detail screen,
 * and Today's Doses card never drift apart.
 *
 * Locale-pinned via the JVM default since `%.1f` is locale-sensitive — CI
 * runs in en_US by default but a contributor with a comma-decimal locale
 * would silently start producing "12,5 mg" otherwise.
 */
class DoseFormatterTest {

    @Test fun `whole doses render as integers`() {
        Locale.setDefault(Locale.US)
        assertEquals("200 mg", DoseFormatter.format(200.0, "mg"))
        assertEquals("100 IU", DoseFormatter.format(100.0, "IU"))
        assertEquals("1 ml", DoseFormatter.format(1.0, "ml"))
    }

    @Test fun `fractional doses render with one decimal`() {
        Locale.setDefault(Locale.US)
        assertEquals("0.5 mg", DoseFormatter.format(0.5, "mg"))
        assertEquals("12.5 mg", DoseFormatter.format(12.5, "mg"))
        assertEquals("0.5 ml", DoseFormatter.format(0.5, "ml"))
    }

    @Test fun `formatDoseOnly skips the unit`() {
        Locale.setDefault(Locale.US)
        assertEquals("200", DoseFormatter.formatDoseOnly(200.0))
        assertEquals("0.5", DoseFormatter.formatDoseOnly(0.5))
    }
}
