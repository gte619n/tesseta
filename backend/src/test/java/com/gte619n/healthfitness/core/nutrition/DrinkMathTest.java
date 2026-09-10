package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Standard-drink arithmetic (spec §4.1, F-checks F2/F16 math). */
class DrinkMathTest {

    private static final double EPS = 0.05;

    @Test
    void wineFiveOunceGlassIsAboutOneStandardDrink() {
        // 148 ml × 12% × 0.789 ≈ 14.0 g ≈ 1.0 std drink
        double grams = DrinkMath.alcoholGrams(12.0, 148.0);
        assertEquals(14.0, grams, 0.6);
        assertEquals(1.0, DrinkMath.standardDrinks(grams), 0.1);
    }

    @Test
    void spiritShotIsAboutOneStandardDrink() {
        // 44 ml × 40% × 0.789 ≈ 13.9 g
        double grams = DrinkMath.alcoholGrams(40.0, 44.0);
        assertEquals(13.9, grams, 0.2);
        assertEquals(0.99, DrinkMath.standardDrinks(grams), 0.05);
    }

    @Test
    void negroniIsAboutOnePointFiveStandardDrinks() {
        // 90 ml × 26% × 0.789 ≈ 18.5 g ≈ 1.3 std
        double grams = DrinkMath.alcoholGrams(26.0, 90.0);
        assertEquals(18.5, grams, 0.5);
        assertTrue(DrinkMath.standardDrinks(grams) > 1.2, "negroni > 1.2 std");
    }

    @Test
    void zeroOrMissingInputsYieldZero() {
        assertEquals(0.0, DrinkMath.alcoholGrams(null, 100.0));
        assertEquals(0.0, DrinkMath.alcoholGrams(12.0, null));
        assertEquals(0.0, DrinkMath.alcoholGrams(0.0, 100.0));
        assertEquals(0.0, DrinkMath.alcoholGrams(12.0, 0.0));
    }

    @Test
    void alcoholCaloriesAreSevenPerGram() {
        assertEquals(70.0, DrinkMath.alcoholKcal(10.0), EPS);
    }

    @Test
    void servingCaloriesCombineMacroAndAlcohol() {
        // 10 g carbs (mixer) → 40 kcal; 14 g alcohol → 98 kcal; total 138
        Macros mixer = new Macros(null, 0.0, 10.0, 0.0, 0.0, 10.0);
        assertEquals(138.0, DrinkMath.servingCalories(mixer, 14.0), EPS);
    }

    @Test
    void describeRoundsToOneDecimal() {
        AlcoholInfo info = DrinkMath.describe(40.0, 44.0);
        assertEquals(40.0, info.abvPercent());
        assertEquals(44.0, info.servingVolumeMl());
        // stored rounded to 1 dp
        assertEquals(Math.round(info.alcoholGrams() * 10) / 10.0, info.alcoholGrams());
        assertEquals(Math.round(info.standardDrinks() * 10) / 10.0, info.standardDrinks());
    }
}
