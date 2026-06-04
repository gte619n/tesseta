package com.gte619n.healthfitness.core.nutrition;

/**
 * A bundle of macronutrient values. All fields are nullable; null is treated
 * as zero by {@link #plus(Macros)} and {@link #scale(double)} so partial data
 * (e.g. a food with no fiber/sugar) composes cleanly.
 */
public record Macros(
    Double caloriesKcal,
    Double proteinGrams,
    Double carbsGrams,
    Double fatGrams,
    Double fiberGrams,
    Double sugarGrams
) {

    public static Macros zero() {
        return new Macros(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    private static double nz(Double value) {
        return value == null ? 0.0 : value;
    }

    /** Null-safe component-wise sum. Treats null operands as zero. */
    public Macros plus(Macros other) {
        if (other == null) return this;
        return new Macros(
            nz(caloriesKcal) + nz(other.caloriesKcal),
            nz(proteinGrams) + nz(other.proteinGrams),
            nz(carbsGrams) + nz(other.carbsGrams),
            nz(fatGrams) + nz(other.fatGrams),
            nz(fiberGrams) + nz(other.fiberGrams),
            nz(sugarGrams) + nz(other.sugarGrams)
        );
    }

    /** Scales every component by {@code factor}, treating null as zero. */
    public Macros scale(double factor) {
        return new Macros(
            nz(caloriesKcal) * factor,
            nz(proteinGrams) * factor,
            nz(carbsGrams) * factor,
            nz(fatGrams) * factor,
            nz(fiberGrams) * factor,
            nz(sugarGrams) * factor
        );
    }
}
