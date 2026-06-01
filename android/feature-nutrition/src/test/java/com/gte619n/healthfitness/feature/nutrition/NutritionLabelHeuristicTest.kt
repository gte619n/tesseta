package com.gte619n.healthfitness.feature.nutrition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelHeuristicTest {

    @Test
    fun `nutrition facts header alone is enough`() {
        assertTrue(looksLikeNutritionLabel("Nutrition Facts\n8 servings per container"))
    }

    @Test
    fun `several panel markers together count as a label`() {
        val ocr = """
            Serving size 1 cup (240g)
            Calories 230
            Total Fat 8g
            Protein 9g
        """.trimIndent()
        assertTrue(looksLikeNutritionLabel(ocr))
    }

    @Test
    fun `detection is case insensitive`() {
        assertTrue(looksLikeNutritionLabel("NUTRITION FACTS"))
    }

    @Test
    fun `a lone calories word on a menu does not fire`() {
        assertFalse(looksLikeNutritionLabel("Grilled salmon — 420 calories"))
    }

    @Test
    fun `arbitrary scene text does not fire`() {
        assertFalse(looksLikeNutritionLabel("EXIT  OPEN 24 HOURS  AISLE 5"))
    }
}
