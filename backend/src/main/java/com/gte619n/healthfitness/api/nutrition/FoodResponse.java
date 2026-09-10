package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.AlcoholInfo;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import java.util.List;

/** Wire representation of a {@link CatalogFood}. */
public record FoodResponse(
    String foodId,
    String name,
    String brand,
    String barcode,
    String category,
    MacrosDto macrosPer100g,
    List<ServingSizeDto> servingSizes,
    int defaultServingIndex,
    FoodSource source,
    String sourceRef,
    FoodStatus status,
    int confirmationCount,
    String imageUrl,
    FoodImageStatus imageStatus,
    String createdBy,
    /** Non-null only for drinks (IMPL-DRINK-01). */
    AlcoholDto alcohol,
    /**
     * For drinks (IMPL-DRINK-01, IL-13): the macros for ONE default serving,
     * rounded to 1 dp, so the web edit form shows exactly what was entered without
     * re-deriving from {@code macrosPer100g} (which drifts on float round-trips).
     * {@code caloriesKcal} here is the full serving total INCLUDING alcohol; the
     * mixer components (protein/carbs/fat/fiber/sugar) are the per-serving mixer
     * contribution. Null for non-drinks.
     */
    MacrosDto servingMacros
) {
    public static FoodResponse from(CatalogFood f) {
        List<ServingSizeDto> servings = f.servingSizes() == null
            ? List.of()
            : f.servingSizes().stream().map(ServingSizeDto::from).toList();
        return new FoodResponse(
            f.foodId(),
            f.name(),
            f.brand(),
            f.barcode(),
            f.category(),
            MacrosDto.from(f.macrosPer100g()),
            servings,
            f.defaultServingIndex(),
            f.source(),
            f.sourceRef(),
            f.status(),
            f.confirmationCount(),
            f.imageUrl(),
            f.imageStatus(),
            f.createdBy(),
            AlcoholDto.from(f.alcohol()),
            servingMacrosOf(f)
        );
    }

    /** Per-serving macros for a drink (macrosPer100g × servingVolumeMl/100), rounded 1dp. */
    private static MacrosDto servingMacrosOf(CatalogFood f) {
        if (f.alcohol() == null || f.macrosPer100g() == null
            || f.alcohol().servingVolumeMl() == null || f.alcohol().servingVolumeMl() <= 0) {
            return null;
        }
        var serving = f.macrosPer100g().scale(f.alcohol().servingVolumeMl() / 100.0);
        return new MacrosDto(
            round1(serving.caloriesKcal()),
            round1(serving.proteinGrams()),
            round1(serving.carbsGrams()),
            round1(serving.fatGrams()),
            round1(serving.fiberGrams()),
            round1(serving.sugarGrams()));
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }

    /** Wire representation of {@link AlcoholInfo}. */
    public record AlcoholDto(
        Double abvPercent,
        Double servingVolumeMl,
        Double alcoholGrams,
        Double standardDrinks
    ) {
        public static AlcoholDto from(AlcoholInfo a) {
            if (a == null) return null;
            return new AlcoholDto(
                a.abvPercent(), a.servingVolumeMl(), a.alcoholGrams(), a.standardDrinks());
        }
    }
}
