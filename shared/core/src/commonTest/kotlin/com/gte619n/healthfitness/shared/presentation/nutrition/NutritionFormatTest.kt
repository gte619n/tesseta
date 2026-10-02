package com.gte619n.healthfitness.shared.presentation.nutrition

import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.forPortion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * IMPL-IOS-01 Phase 3 Wave C — port of the Android MacroFormatTest, plus the
 * label-heuristic gate. Macro/portion math is the XPLAT divergence surface, so
 * these lock it to the same numbers on both clients.
 */
class NutritionFormatTest {

    private val per100 = Macros(
        caloriesKcal = 200.0,
        proteinGrams = 10.0,
        carbsGrams = 20.0,
        fatGrams = 5.0,
        fiberGrams = 3.0,
        sugarGrams = 8.0,
    )

    @Test
    fun forPortionScalesByGramsTimesQuantityOver100() {
        val out = per100.forPortion(servingGrams = 240.0, quantity = 1.0)
        assertEquals(480.0, out.caloriesKcal!!, 1e-9)
        assertEquals(24.0, out.proteinGrams!!, 1e-9)
        assertEquals(48.0, out.carbsGrams!!, 1e-9)
        assertEquals(12.0, out.fatGrams!!, 1e-9)
        assertEquals(7.2, out.fiberGrams!!, 1e-9)
        assertEquals(19.2, out.sugarGrams!!, 1e-9)
    }

    @Test
    fun forPortionKeepsNullNutrientsNull() {
        val sparse = Macros(caloriesKcal = 50.0)
        val out = sparse.forPortion(servingGrams = 200.0, quantity = 2.0)
        assertEquals(200.0, out.caloriesKcal!!, 1e-9)
        assertNull(out.proteinGrams)
        assertNull(out.fatGrams)
    }

    @Test
    fun formatGramsRoundsAndAddsUnitNullShowsEmDash() {
        assertEquals("24 g", formatGrams(23.6))
        assertEquals("—", formatGrams(null))
    }

    @Test
    fun formatKcalRoundsAndAddsUnit() {
        assertEquals("480 kcal", formatKcal(480.4))
        assertEquals("—", formatKcal(null))
    }

    @Test
    fun largeValuesAreGroupedWithCommas() {
        assertEquals("2,450 kcal", formatKcal(2450.0))
        assertEquals("1,200 g", formatGrams(1200.0))
        assertEquals("1,000,000", formatWholeNumber(1_000_000.0))
    }

    @Test
    fun progressFractionClampsAndHandlesMissingTarget() {
        assertEquals(0.5f, progressFraction(100.0, 200.0)!!, 1e-6f)
        assertEquals(1f, progressFraction(300.0, 200.0)!!, 1e-6f)
        assertNull(progressFraction(100.0, null))
        assertNull(progressFraction(100.0, 0.0))
    }

    @Test
    fun remainingNeverGoesNegative() {
        assertEquals(50.0, remaining(150.0, 200.0)!!, 1e-9)
        assertEquals(0.0, remaining(300.0, 200.0)!!, 1e-9)
        assertNull(remaining(100.0, null))
    }

    @Test
    fun isOverOnlyTrueWhenConsumedExceedsPositiveTarget() {
        assertTrue(isOver(250.0, 200.0))
        assertFalse(isOver(150.0, 200.0))
        assertFalse(isOver(100.0, null))
        assertFalse(isOver(100.0, 0.0))
    }

    @Test
    fun nutrientRowAccessorsAndFormatting() {
        assertEquals(200.0, NutrientRow.CALORIES.valueOf(per100))
        assertEquals("10 g", NutrientRow.PROTEIN.format(per100.proteinGrams))
        assertEquals("200 kcal", NutrientRow.CALORIES.format(per100.caloriesKcal))
    }

    @Test
    fun labelHeuristicNeedsThreeMarkersOrTheFactsHeader() {
        assertTrue(looksLikeNutritionLabel("NUTRITION FACTS\nServing size 30g"))
        assertTrue(looksLikeNutritionLabel("Serving size 30g\nCalories 120\nProtein 8g"))
        assertFalse(looksLikeNutritionLabel("Just a grocery receipt with total"))
        assertFalse(looksLikeNutritionLabel("Calories 120")) // one marker only
    }
}
