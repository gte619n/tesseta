package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One logged food on a given day and meal. {@code macros} is a snapshot frozen
 * at log time so editing the underlying catalog food never rewrites history.
 *
 * <p>A meal logged from a photo is a <em>composite</em> entry: {@code foodId} is
 * null, {@code ingredients} holds its components (each with its own
 * raw-ingredient image), and {@code mealImageUrl}/{@code mealImageStatus} carry
 * the generated image of the finished plated meal. A plain single-food entry
 * leaves {@code ingredients} null and the meal-image fields unset.
 */
public record FoodEntry(
    String userId,
    LocalDate date,
    String entryId,
    MealType meal,
    String foodId,
    String foodName,
    String servingLabel,
    Double servingGrams,
    Double quantity,
    Macros macros,
    String photoRef,
    EntrySource source,
    List<CompositeIngredient> ingredients,
    String mealImageUrl,
    FoodImageStatus mealImageStatus,
    Instant createdAt,
    Instant updatedAt
) {
    /** True when this entry is a photo-logged meal with sub-ingredients. */
    public boolean isComposite() {
        return ingredients != null && !ingredients.isEmpty();
    }
}
