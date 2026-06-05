package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.CompositeIngredient;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.MealDescriptionService.MealResolution;
import java.util.List;

/**
 * Response for {@code POST /api/nutrition/describe}: the resolved meal — either a
 * previously-saved match ({@code matched = true}) or a freshly created one — with
 * its macros, ingredient breakdown and studio-photo status. The meal is saved in
 * the shared catalog; the client logs it onto a day via
 * {@code POST /api/me/nutrition/{date}/describe-meal} with this {@code mealId}.
 */
public record DescribedMealResponse(
    String mealId,
    boolean matched,
    String name,
    Double totalGrams,
    MacrosDto macros,
    String imageUrl,
    FoodImageStatus imageStatus,
    List<DescribedIngredient> ingredients
) {

    public static DescribedMealResponse from(MealResolution r) {
        List<DescribedIngredient> ingredients = r.ingredients() == null
            ? List.of()
            : r.ingredients().stream().map(DescribedIngredient::from).toList();
        return new DescribedMealResponse(
            r.mealId(),
            r.matched(),
            r.name(),
            r.totalGrams(),
            MacrosDto.from(r.macros()),
            r.imageUrl(),
            r.imageStatus() != null ? r.imageStatus() : FoodImageStatus.NONE,
            ingredients);
    }

    /** One component of the described meal, with its frozen per-100g baseline. */
    public record DescribedIngredient(
        String name,
        Double servingGrams,
        String servingLabel,
        Double quantity,
        MacrosDto macros,
        MacrosDto macrosPer100g
    ) {
        static DescribedIngredient from(CompositeIngredient ing) {
            return new DescribedIngredient(
                ing.name(),
                ing.servingGrams(),
                ing.servingLabel(),
                ing.quantity(),
                MacrosDto.from(ing.macros()),
                MacrosDto.from(ing.macrosPer100g()));
        }
    }
}
