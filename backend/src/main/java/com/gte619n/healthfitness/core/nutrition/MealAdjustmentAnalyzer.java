package com.gte619n.healthfitness.core.nutrition;

import java.util.List;

/**
 * Port for <em>correcting</em> an already-identified meal from a free-text
 * instruction — e.g. "that's pearl couscous, not lentils", "remove the bread",
 * "the chicken was about 200 g". The model is given the current identification
 * (name + itemized components with portions and per-100 g macros), the user's
 * instruction, and — when the meal was logged from a photo — the original photo
 * as a visual reference, and returns a revised {@link MealPhotoAnalyzer.MealAnalysis}.
 *
 * <p>The model decides whether the instruction is a <em>targeted</em> fix (change
 * only what it refers to, keep every other item exactly) or a <em>full</em>
 * re-identification; the caller doesn't have to.
 *
 * <p>Defined in {@code core} so {@code MealAdjustmentService} depends on the
 * abstraction, not the Gemini SDK. The concrete implementation lives in
 * {@code integrations} ({@code MealPhotoExtractor}, {@code gemini-3.8-flash}
 * tool calling) and is injected via {@code ObjectProvider}, mirroring
 * {@link MealPhotoAnalyzer}. On an extraction failure the implementation throws
 * (rather than returning empty) so the controller maps it to a 422.
 */
public interface MealAdjustmentAnalyzer {

    /**
     * Produce a corrected identification of {@code current} per {@code instruction}.
     *
     * @param current     the meal as currently identified
     * @param instruction the user's free-text correction (required, non-blank)
     * @param photoBytes  the original meal photo, or null for a text-only entry
     * @param mimeType    the photo content type (ignored when {@code photoBytes} is null)
     * @return the revised analysis (name, packaged flag, items)
     */
    MealPhotoAnalyzer.MealAnalysis adjust(
        MealContext current, String instruction, byte[] photoBytes, String mimeType);

    /**
     * The meal as it stands today, handed to the model as the thing to correct.
     *
     * @param mealName        current display name (e.g. "Lentils and rice")
     * @param packagedProduct whether it is currently a single packaged product
     * @param items           the current components
     */
    record MealContext(String mealName, boolean packagedProduct, List<Item> items) {

        /**
         * One current component.
         *
         * @param name          e.g. "Lentils"
         * @param portionGrams  the portion weight as served, in grams (nullable)
         * @param macrosPer100g macros per 100 g of the food (nullable)
         */
        public record Item(String name, Double portionGrams, Macros macrosPer100g) {}
    }
}
