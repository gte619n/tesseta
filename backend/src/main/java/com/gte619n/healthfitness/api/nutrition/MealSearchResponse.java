package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.SavedMeal;

/**
 * Wire representation of a {@link SavedMeal} as an add-food search result. The
 * add flow logs the meal by {@code mealId} via {@code POST /{date}/describe-meal}
 * (reusing its ingredient breakdown and plated photo), so this carries only what
 * the search row needs to render and to log on tap. {@code macros}/{@code
 * totalGrams} are the summed totals for one serving of the meal.
 */
public record MealSearchResponse(
    String mealId,
    String name,
    MacrosDto macros,
    Double totalGrams,
    String imageUrl,
    FoodImageStatus imageStatus,
    boolean mine
) {
    public static MealSearchResponse from(SavedMeal m, String userId) {
        return new MealSearchResponse(
            m.mealId(),
            m.name(),
            MacrosDto.from(m.macros()),
            m.totalGrams(),
            m.imageUrl(),
            m.imageStatus(),
            userId.equals(m.createdBy())
        );
    }
}
