package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.util.List;

/**
 * A reusable, named meal in the shared meal catalog — the durable artifact
 * behind "describe a meal": the first time a description doesn't match anything,
 * the itemized result is saved here so any later description of the same dish
 * reuses its macros, ingredient breakdown and studio photo instead of
 * regenerating them.
 *
 * <p>Lives in a TOP-LEVEL {@code mealCatalog} collection (shared across users,
 * like {@link CatalogFood}), tagged with the creating user in {@code createdBy}
 * so search can surface a user's own meals first while still allowing global
 * reuse. {@code ingredients} carries the full per-component breakdown (each
 * already portioned), and {@code macros}/{@code totalGrams} are the summed
 * portion totals for one serving of the meal.
 *
 * <p>{@code imageUrl}/{@code imageStatus} hold the generated plated-dish photo,
 * managed by {@code SavedMealImageService} exactly as {@link FoodImageService}
 * manages a catalog food's image. Timestamps follow the codebase convention:
 * the record carries {@code null} and the Firestore repo stamps server time.
 */
public record SavedMeal(
    String mealId,
    String name,
    String nameLower,
    String createdBy,
    List<CompositeIngredient> ingredients,
    Double totalGrams,
    Macros macros,
    FoodSource source,
    String imageUrl,
    FoodImageStatus imageStatus,
    Instant createdAt,
    Instant updatedAt
) {
    /** A copy with a new image url + status (used by the image service). */
    public SavedMeal withImage(String newImageUrl, FoodImageStatus newStatus) {
        return new SavedMeal(
            mealId, name, nameLower, createdBy, ingredients, totalGrams, macros,
            source, newImageUrl != null ? newImageUrl : imageUrl, newStatus,
            createdAt, Instant.now());
    }
}
