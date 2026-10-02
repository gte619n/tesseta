package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit-tests {@link Macros#withDerivedCalories()} — the 4/4/9 + 7·alcohol invariant. */
class MacrosTest {

    @Test
    void derivesCaloriesFromMacros() {
        Macros m = new Macros(9999.0, 30.0, 50.0, 10.0, 5.0, 12.0).withDerivedCalories();
        assertEquals(30 * 4 + 50 * 4 + 10 * 9, m.caloriesKcal(), 1e-9);
        // Other components are untouched.
        assertEquals(30.0, m.proteinGrams(), 1e-9);
        assertEquals(5.0, m.fiberGrams(), 1e-9);
    }

    @Test
    void treatsNullMacroComponentsAsZero() {
        Macros m = new Macros(null, 25.0, null, null, null, null).withDerivedCalories();
        assertEquals(100.0, m.caloriesKcal(), 1e-9);
    }

    @Test
    void keepsSuppliedCalories_whenNoMacrosAtAll() {
        Macros caloriesOnly = new Macros(150.0, null, null, null, null, null).withDerivedCalories();
        assertEquals(150.0, caloriesOnly.caloriesKcal(), 1e-9);
        assertNull(caloriesOnly.proteinGrams());
    }

    @Test
    void zeroMacros_deriveToZeroCalories() {
        Macros m = new Macros(500.0, 0.0, 0.0, 0.0, 0.0, 0.0).withDerivedCalories();
        assertEquals(0.0, m.caloriesKcal(), 1e-9);
    }

    @Test
    void foldsAlcoholCaloriesAtSevenPerGram() {
        // The bug: a gin & soda (no protein/carbs/fat, ~14 g alcohol) must NOT
        // report 0 kcal. It should derive 14 × 7 = 98 kcal.
        Macros ginSoda =
            new Macros(9999.0, 0.0, 0.0, 0.0, 0.0, 0.0, 14.0).withDerivedCalories();
        assertEquals(98.0, ginSoda.caloriesKcal(), 1e-9);
        assertEquals(14.0, ginSoda.alcoholGrams(), 1e-9);
    }

    @Test
    void addsAlcoholOnTopOfMacros() {
        // A 16 oz beer: ~19 g carbs (76 kcal) + ~18 g alcohol (126 kcal) = 202.
        Macros beer =
            new Macros(77.0, 0.0, 19.0, 0.0, 0.0, 0.0, 18.0).withDerivedCalories();
        assertEquals(19 * 4 + 18 * 7, beer.caloriesKcal(), 1e-9);
    }

    @Test
    void neatSpirit_withOnlyAlcohol_derivesRatherThanKeepingRawCalories() {
        // alcohol-only still counts as "has macros", so calories are derived
        // (not left at the supplied value) — alcohol is the sole energy source.
        Macros vodka = new Macros(0.0, null, null, null, null, null, 10.0).withDerivedCalories();
        assertEquals(70.0, vodka.caloriesKcal(), 1e-9);
    }

    @Test
    void alcoholScalesAndSumsComponentWise() {
        Macros perServing = new Macros(0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 14.0);
        Macros doubled = perServing.scale(2.0);
        assertEquals(28.0, doubled.alcoholGrams(), 1e-9);
        Macros summed = perServing.plus(perServing);
        assertEquals(28.0, summed.alcoholGrams(), 1e-9);
    }

    @Test
    void sixArgConstructor_defaultsAlcoholToNull() {
        Macros m = new Macros(100.0, 5.0, 10.0, 2.0, 1.0, 3.0);
        assertNull(m.alcoholGrams());
    }
}
