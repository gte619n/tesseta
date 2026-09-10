package com.gte619n.healthfitness.core.nutrition;

/**
 * Pure standard-drink arithmetic for IMPL-DRINK-01 (spec §4.1). The single source
 * of truth for turning ABV% + serving volume into alcohol grams, US standard
 * drinks and alcohol calories. Deterministic and auditable (D10) — the AI never
 * does this arithmetic.
 *
 * <pre>
 *   pureEthanolMl  = volumeMl × (abv/100)
 *   alcoholGrams   = pureEthanolMl × 0.789   (ethanol density g/ml)
 *   standardDrinks = alcoholGrams / 14        (US standard drink = 14 g)
 *   alcoholKcal    = alcoholGrams × 7
 * </pre>
 */
public final class DrinkMath {

    /** Ethanol density, grams per millilitre. */
    public static final double ETHANOL_DENSITY_G_PER_ML = 0.789;

    /** Grams of pure alcohol in one US standard drink. */
    public static final double GRAMS_PER_STANDARD_DRINK = 14.0;

    /** Calories per gram of pure alcohol. */
    public static final double KCAL_PER_GRAM_ALCOHOL = 7.0;

    private DrinkMath() {}

    /** Grams of pure alcohol in a serving. Returns 0 when either input is missing/≤0. */
    public static double alcoholGrams(Double abvPercent, Double servingVolumeMl) {
        double abv = abvPercent == null ? 0.0 : abvPercent;
        double ml = servingVolumeMl == null ? 0.0 : servingVolumeMl;
        if (abv <= 0.0 || ml <= 0.0) {
            return 0.0;
        }
        return ml * (abv / 100.0) * ETHANOL_DENSITY_G_PER_ML;
    }

    /** US standard drinks for a given alcohol-gram amount. */
    public static double standardDrinks(double alcoholGrams) {
        return alcoholGrams / GRAMS_PER_STANDARD_DRINK;
    }

    /** Calories contributed by a given alcohol-gram amount. */
    public static double alcoholKcal(double alcoholGrams) {
        return alcoholGrams * KCAL_PER_GRAM_ALCOHOL;
    }

    /**
     * Build the derived {@link AlcoholInfo} for a serving from its ABV% and volume.
     * {@code alcoholGrams}/{@code standardDrinks} are rounded to 1 decimal for
     * storage/display; full precision is not needed downstream.
     */
    public static AlcoholInfo describe(Double abvPercent, Double servingVolumeMl) {
        double grams = alcoholGrams(abvPercent, servingVolumeMl);
        return new AlcoholInfo(
            abvPercent,
            servingVolumeMl,
            round1(grams),
            round1(standardDrinks(grams)));
    }

    /** Total serving calories = macro calories (4/4/9) + alcohol calories (7/g). */
    public static double servingCalories(Macros macroContribution, double alcoholGrams) {
        double macroKcal = 0.0;
        if (macroContribution != null) {
            macroKcal = nz(macroContribution.proteinGrams()) * Macros.KCAL_PER_GRAM_PROTEIN
                + nz(macroContribution.carbsGrams()) * Macros.KCAL_PER_GRAM_CARBS
                + nz(macroContribution.fatGrams()) * Macros.KCAL_PER_GRAM_FAT;
        }
        return macroKcal + alcoholKcal(alcoholGrams);
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
