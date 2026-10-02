package com.gte619n.healthfitness.core.nutrition;

/**
 * A bundle of macronutrient values. All fields are nullable; null is treated
 * as zero by {@link #plus(Macros)} and {@link #scale(double)} so partial data
 * (e.g. a food with no fiber/sugar) composes cleanly.
 *
 * <p>{@code alcoholGrams} is grams of pure ethanol. It sits OUTSIDE the 4/4/9
 * macro split and contributes 7 kcal/g, so {@link #withDerivedCalories()} folds
 * it into the calorie total. Without it, any alcoholic item (a neat spirit, a
 * beer, a cocktail ingredient) would report its alcohol calories as zero once
 * calories are re-derived from protein/carbs/fat. It is null for the vast
 * majority of foods; like every other field it scales and sums component-wise.
 */
public record Macros(
    Double caloriesKcal,
    Double proteinGrams,
    Double carbsGrams,
    Double fatGrams,
    Double fiberGrams,
    Double sugarGrams,
    Double alcoholGrams
) {

    /**
     * Backward-compatible constructor for the (still common) non-alcoholic case:
     * every existing {@code new Macros(cal, p, c, f, fiber, sugar)} call keeps
     * compiling, with {@code alcoholGrams} defaulting to null.
     */
    public Macros(
        Double caloriesKcal,
        Double proteinGrams,
        Double carbsGrams,
        Double fatGrams,
        Double fiberGrams,
        Double sugarGrams
    ) {
        this(caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams, null);
    }

    public static Macros zero() {
        return new Macros(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
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
            nz(sugarGrams) + nz(other.sugarGrams),
            nz(alcoholGrams) + nz(other.alcoholGrams)
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
            nz(sugarGrams) * factor,
            nz(alcoholGrams) * factor
        );
    }

    /** Atwater factors (kcal per gram). Carbs include fiber, per US label convention. */
    public static final double KCAL_PER_GRAM_PROTEIN = 4.0;
    public static final double KCAL_PER_GRAM_CARBS = 4.0;
    public static final double KCAL_PER_GRAM_FAT = 9.0;
    /** Pure ethanol energy density; alcohol is not one of the 4/4/9 macros. */
    public static final double KCAL_PER_GRAM_ALCOHOL = 7.0;

    /**
     * Returns a copy whose calories are derived from the macros
     * (4·protein + 4·carbs + 9·fat + 7·alcohol), so calories and macros can
     * never disagree. When protein, carbs, fat AND alcohol are ALL null the
     * supplied calories are kept as-is — a calories-only quick add (e.g. a food
     * the user only knows the kcal of) stays loggable. A neat spirit, whose only
     * energy is alcohol, still derives its calories from {@code alcoholGrams}.
     */
    public Macros withDerivedCalories() {
        if (proteinGrams == null && carbsGrams == null
            && fatGrams == null && alcoholGrams == null) {
            return this;
        }
        double derived = nz(proteinGrams) * KCAL_PER_GRAM_PROTEIN
            + nz(carbsGrams) * KCAL_PER_GRAM_CARBS
            + nz(fatGrams) * KCAL_PER_GRAM_FAT
            + nz(alcoholGrams) * KCAL_PER_GRAM_ALCOHOL;
        return new Macros(
            derived, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams, alcoholGrams);
    }
}
