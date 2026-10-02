package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.Macros;

/** Wire representation of a {@link Macros} bundle. */
public record MacrosDto(
    Double caloriesKcal,
    Double proteinGrams,
    Double carbsGrams,
    Double fatGrams,
    Double fiberGrams,
    Double sugarGrams,
    // Grams of pure ethanol (null for non-alcoholic foods); carries the
    // alcohol calories that would otherwise be dropped by 4/4/9 re-derivation.
    Double alcoholGrams
) {
    public static MacrosDto from(Macros m) {
        if (m == null) return null;
        return new MacrosDto(
            m.caloriesKcal(),
            m.proteinGrams(),
            m.carbsGrams(),
            m.fatGrams(),
            m.fiberGrams(),
            m.sugarGrams(),
            m.alcoholGrams()
        );
    }

    public Macros toMacros() {
        return new Macros(
            caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams, alcoholGrams);
    }
}
