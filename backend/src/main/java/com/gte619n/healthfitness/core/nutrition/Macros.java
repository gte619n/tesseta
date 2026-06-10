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

    /** Atwater factors (kcal per gram). Carbs include fiber, per US label convention. */
    public static final double KCAL_PER_GRAM_PROTEIN = 4.0;
    public static final double KCAL_PER_GRAM_CARBS = 4.0;
    public static final double KCAL_PER_GRAM_FAT = 9.0;

    /**
     * Returns a copy whose calories are derived from the macros
     * (4·protein + 4·carbs + 9·fat), so calories and macros can never
     * disagree. When protein, carbs and fat are ALL null the supplied
     * calories are kept as-is — a calories-only quick add (e.g. a drink the
     * user only knows the kcal of) stays loggable.
     */
    public Macros withDerivedCalories() {
        if (proteinGrams == null && carbsGrams == null && fatGrams == null) {
            return this;
        }
        double derived = nz(proteinGrams) * KCAL_PER_GRAM_PROTEIN
            + nz(carbsGrams) * KCAL_PER_GRAM_CARBS
            + nz(fatGrams) * KCAL_PER_GRAM_FAT;
        return new Macros(derived, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams);
    }
}
