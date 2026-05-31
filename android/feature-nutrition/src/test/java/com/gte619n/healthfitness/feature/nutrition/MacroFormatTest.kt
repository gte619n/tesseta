package com.gte619n.healthfitness.feature.nutrition

import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.forPortion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacroFormatTest {

    private val per100 = Macros(
        caloriesKcal = 200.0,
        proteinGrams = 10.0,
        carbsGrams = 20.0,
        fatGrams = 5.0,
        fiberGrams = 3.0,
        sugarGrams = 8.0,
    )

    @Test
    fun `forPortion scales by grams times quantity over 100`() {
        // 240 g × 1× → factor 2.4
        val out = per100.forPortion(servingGrams = 240.0, quantity = 1.0)
        assertEquals(480.0, out.caloriesKcal!!, 1e-9)
        assertEquals(24.0, out.proteinGrams!!, 1e-9)
        assertEquals(48.0, out.carbsGrams!!, 1e-9)
        assertEquals(12.0, out.fatGrams!!, 1e-9)
        assertEquals(7.2, out.fiberGrams!!, 1e-9)
        assertEquals(19.2, out.sugarGrams!!, 1e-9)
    }

    @Test
    fun `forPortion respects the quantity step`() {
        // 100 g × 0.5× → factor 0.5
        val out = per100.forPortion(servingGrams = 100.0, quantity = 0.5)
        assertEquals(100.0, out.caloriesKcal!!, 1e-9)
        assertEquals(5.0, out.proteinGrams!!, 1e-9)
    }

    @Test
    fun `forPortion keeps null nutrients null`() {
        val sparse = Macros(caloriesKcal = 50.0) // protein/carbs/fat/etc null
        val out = sparse.forPortion(servingGrams = 200.0, quantity = 2.0)
        assertEquals(200.0, out.caloriesKcal!!, 1e-9) // 50 × (200×2/100) = 200
        assertNull(out.proteinGrams)
        assertNull(out.fatGrams)
    }

    @Test
    fun `formatGrams rounds and adds unit, null shows em dash`() {
        assertEquals("24 g", formatGrams(23.6))
        assertEquals("—", formatGrams(null))
    }

    @Test
    fun `formatKcal rounds and adds unit`() {
        assertEquals("480 kcal", formatKcal(480.4))
        assertEquals("—", formatKcal(null))
    }

    @Test
    fun `progressFraction clamps and handles missing target`() {
        assertEquals(0.5f, progressFraction(100.0, 200.0)!!, 1e-6f)
        assertEquals(1f, progressFraction(300.0, 200.0)!!, 1e-6f) // clamped
        assertNull(progressFraction(100.0, null))
        assertNull(progressFraction(100.0, 0.0))
    }

    @Test
    fun `remaining never goes negative`() {
        assertEquals(50.0, remaining(150.0, 200.0)!!, 1e-9)
        assertEquals(0.0, remaining(300.0, 200.0)!!, 1e-9)
        assertNull(remaining(100.0, null))
    }
}
