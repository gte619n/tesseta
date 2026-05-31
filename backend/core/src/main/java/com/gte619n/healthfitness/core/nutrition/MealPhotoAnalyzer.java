package com.gte619n.healthfitness.core.nutrition;

import java.util.List;

/**
 * Port for itemizing a full-meal photo into its food components.
 *
 * <p>Defined in {@code core} so {@link NutritionCaptureService} can depend on
 * the abstraction without pulling {@code integrations} (and its Gemini SDK
 * dependency) into the {@code core} layer. The concrete implementation
 * ({@code MealPhotoExtractor}, {@code gemini-3.5-flash} tool calling) lives in
 * {@code integrations} and is injected via {@code ObjectProvider} so core unit
 * tests construct the service without it — mirroring the {@link BarcodeLookup}
 * seam.
 *
 * <p>On an extraction failure the implementation throws (rather than returning
 * empty), so the controller can map it to a 422. The list may be empty when the
 * model found no identifiable food.
 */
public interface MealPhotoAnalyzer {

    /**
     * Identify the distinct food components on the plate.
     *
     * @param imageBytes the raw meal photo
     * @param mimeType   the image content type (e.g. {@code image/jpeg})
     * @return one entry per identified component (possibly empty)
     */
    List<MealItem> analyze(byte[] imageBytes, String mimeType);

    /**
     * A single proposed food component identified on the plate.
     *
     * @param name                 human-readable name, e.g. "Grilled chicken breast"
     * @param estimatedPortionGrams estimated weight of this component, in grams
     * @param macrosPer100g        macros per 100 g for this food
     * @param confidence           model confidence in {@code [0,1]} (nullable)
     */
    record MealItem(
        String name,
        Double estimatedPortionGrams,
        Macros macrosPer100g,
        Double confidence
    ) {}
}
