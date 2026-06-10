package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit-tests {@link Macros#withDerivedCalories()} — the 4/4/9 invariant. */
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
}
