package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.Macros;

/** Wire representation of a {@link Macros} bundle. */
public record MacrosDto(
    Double caloriesKcal,
    Double proteinGrams,
    Double carbsGrams,
    Double fatGrams,
    Double fiberGrams,
    Double sugarGrams
) {
    public static MacrosDto from(Macros m) {
        if (m == null) return null;
        return new MacrosDto(
            m.caloriesKcal(),
            m.proteinGrams(),
            m.carbsGrams(),
            m.fatGrams(),
            m.fiberGrams(),
            m.sugarGrams()
        );
    }

    public Macros toMacros() {
        return new Macros(caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams);
    }
}
