package com.gte619n.healthfitness.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IMPL-DRINK-01 (§6.1 "Session tally") — the pure session-tally reducer:
 * N taps → correct Σstd/Σkcal/Σsugar/Σcarbs; Undo (dropping a row) decrements;
 * a ×q log contributes q-scaled figures.
 */
class DrinkTallyTest {

    private fun drink(
        std: Double,
        kcal: Double,
        sugar: Double = 0.0,
        carbs: Double = 0.0,
        quantity: Double = 1.0,
        id: String = "e",
    ) = DrinkSession.LoggedDrink(
        entryId = id,
        foodId = "f",
        name = "Drink",
        standardDrinks = std,
        kcal = kcal,
        sugar = sugar,
        carbs = carbs,
        quantity = quantity,
        atMillis = 0L,
    )

    @Test
    fun `empty session has a zero tally`() {
        val t = DrinkTally.of(emptyList())
        assertEquals(0, t.count)
        assertEquals(0.0, t.standardDrinks, 1e-9)
        assertEquals(0.0, t.kcal, 1e-9)
    }

    @Test
    fun `three taps sum standard drinks and calories`() {
        val drinks = listOf(
            drink(std = 1.5, kcal = 210.0, sugar = 0.0, carbs = 4.0, id = "a"),
            drink(std = 1.0, kcal = 210.0, sugar = 1.0, carbs = 18.0, id = "b"),
            drink(std = 1.0, kcal = 210.0, sugar = 1.0, carbs = 18.0, id = "c"),
        )
        val t = DrinkTally.of(drinks)
        assertEquals(3, t.count)
        assertEquals(3.5, t.standardDrinks, 1e-9)
        assertEquals(630.0, t.kcal, 1e-9)
        assertEquals(2.0, t.sugar, 1e-9)
        assertEquals(40.0, t.carbs, 1e-9)
    }

    @Test
    fun `undo decrements the tally`() {
        val drinks = listOf(
            drink(std = 1.5, kcal = 210.0, id = "a"),
            drink(std = 1.0, kcal = 150.0, id = "b"),
        )
        val afterUndo = drinks.filterNot { it.entryId == "b" }
        val t = DrinkTally.of(afterUndo)
        assertEquals(1, t.count)
        assertEquals(1.5, t.standardDrinks, 1e-9)
        assertEquals(210.0, t.kcal, 1e-9)
    }

    @Test
    fun `multiplier-scaled logs already carry q-scaled figures`() {
        // A ×2 log freezes 2× std + 2× kcal (see planDrinkLog); the reducer just sums.
        val drinks = listOf(
            drink(std = 1.0, kcal = 100.0, quantity = 1.0, id = "a"),
            drink(std = 2.0, kcal = 200.0, quantity = 2.0, id = "b"),
            drink(std = 0.5, kcal = 50.0, quantity = 0.5, id = "c"),
        )
        val t = DrinkTally.of(drinks)
        assertEquals(3, t.count)
        assertEquals(3.5, t.standardDrinks, 1e-9)
        assertEquals(350.0, t.kcal, 1e-9)
    }
}
