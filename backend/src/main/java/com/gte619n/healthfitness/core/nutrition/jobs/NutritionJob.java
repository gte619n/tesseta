package com.gte619n.healthfitness.core.nutrition.jobs;

/**
 * A durable unit of background work (Tier 3). Fields are reused per
 * {@link NutritionJobType} (see the factory methods); only small identifiers and
 * storage references travel on the queue — never image bytes, which stay in GCS
 * and are re-read by the handler from {@code ref}. This keeps every job well
 * under the Cloud Tasks payload limit and makes redelivery cheap.
 *
 * <p>Jobs must be safe to run more than once: a durable queue delivers
 * at-least-once, so handlers early-return when the target is already in a
 * terminal state (see the {@code *OrThrow} work methods).
 */
public record NutritionJob(
    NutritionJobType type,
    String id,
    String userId,
    String date,
    String ref,
    String name,
    String mime
) {

    /** Catalog-food studio image; {@code ref} is the optional meal-photo reference. */
    public static NutritionJob foodImage(String foodId, String referencePhotoRef) {
        return new NutritionJob(NutritionJobType.FOOD_IMAGE, foodId, null, null, referencePhotoRef, null, null);
    }

    /** Composite-entry finished-meal image. */
    public static NutritionJob entryImage(
        String userId, String date, String entryId, String mealName, String referencePhotoRef) {
        return new NutritionJob(
            NutritionJobType.ENTRY_IMAGE, entryId, userId, date, referencePhotoRef, mealName, null);
    }

    /** Saved-meal plated-dish image. */
    public static NutritionJob savedMealImage(String mealId) {
        return new NutritionJob(NutritionJobType.SAVED_MEAL_IMAGE, mealId, null, null, null, null, null);
    }

    /** Captured-photo analysis; {@code ref} is the stored photo, {@code mime} its type. */
    public static NutritionJob mealAnalysis(
        String userId, String date, String entryId, String photoRef, String mime) {
        return new NutritionJob(
            NutritionJobType.MEAL_ANALYSIS, entryId, userId, date, photoRef, null, mime);
    }

    /** Described-meal resolution; {@code name} carries the user's description. */
    public static NutritionJob descriptionAnalysis(
        String userId, String date, String entryId, String description) {
        return new NutritionJob(
            NutritionJobType.DESCRIPTION_ANALYSIS, entryId, userId, date, null, description, null);
    }
}
