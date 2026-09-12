package com.gte619n.healthfitness.core.nutrition;

import java.util.List;

/**
 * Port for estimating how much of an already-logged meal was actually eaten by
 * comparing the <strong>original</strong> meal photo with a new photo of the
 * <strong>leftovers</strong> on the plate (IMPL-LEFTOVER-01, spec D1). The model
 * is given the meal's as-served itemization plus both images, and returns, per
 * item, how much is <em>left</em>.
 *
 * <p>Defined in {@code core} so {@code LeftoverService} depends on the
 * abstraction, not the Gemini SDK. The concrete implementation lives in
 * {@code integrations} ({@code LeftoverPhotoExtractor}, {@code gemini-3.8-flash}
 * tool calling) and is injected via {@code ObjectProvider}, mirroring
 * {@link MealAdjustmentAnalyzer}. On an extraction failure the implementation
 * throws (so the job can mark the pass rejected/retriable).
 */
public interface LeftoverAnalyzer {

    /**
     * Estimate the remaining (uneaten) portion of each served item.
     *
     * @param served        the meal as served (name + per-item grams)
     * @param originalPhoto the original meal photo bytes (required for D1 compare)
     * @param originalMime  the original photo content type
     * @param leftoverPhoto the new leftover photo bytes
     * @param leftoverMime  the leftover photo content type
     * @return per-item remaining estimate + an overall confidence in {@code [0,1]}
     */
    LeftoverEstimate estimate(
        ServedMeal served,
        byte[] originalPhoto,
        String originalMime,
        byte[] leftoverPhoto,
        String leftoverMime);

    /** The meal as served, handed to the model as the thing to compare against. */
    record ServedMeal(String mealName, List<Item> items) {
        /** One served component. {@code servedGrams} is its as-served weight. */
        public record Item(String name, Double servedGrams) {}
    }

    /**
     * The model's estimate of what is left on the plate.
     *
     * @param items             per-item remaining estimates
     * @param overallConfidence how confident the model is overall, in {@code [0,1]}
     */
    record LeftoverEstimate(List<ItemEstimate> items, double overallConfidence) {

        /**
         * One item's remaining estimate.
         *
         * @param name           the served item this refers to (matched by name)
         * @param remainingGrams grams still on the plate (0 = all eaten)
         * @param matched        whether the model could locate this served item
         * @param confidence     per-item confidence in {@code [0,1]}
         */
        public record ItemEstimate(
            String name, Double remainingGrams, boolean matched, double confidence) {}
    }
}
