package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;

/**
 * Port for turning a free-text meal <em>description</em> ("grilled chicken with
 * rice and broccoli") into its itemized food components — the text-input
 * sibling of {@link MealPhotoAnalyzer}.
 *
 * <p>Defined in {@code core} so {@link MealDescriptionService} depends on the
 * abstraction without pulling {@code integrations} (and its Gemini SDK) into the
 * {@code core} layer. The concrete implementation ({@code MealDescriptionExtractor},
 * {@code gemini-3.5-flash} tool calling) lives in {@code integrations} and is
 * injected via {@code ObjectProvider}, mirroring the {@link MealPhotoAnalyzer}
 * seam so core unit tests construct the service without it.
 *
 * <p>Reuses {@link MealPhotoAnalyzer.MealAnalysis}/{@link MealPhotoAnalyzer.MealItem}
 * so a described meal and a photographed meal share one downstream shape. On an
 * extraction failure the implementation throws; the items list may be empty when
 * the model found no identifiable food.
 */
public interface MealDescriptionAnalyzer {

    /**
     * Itemize a free-text meal description into a named meal plus its components
     * (each with per-100g macros and an estimated portion).
     *
     * @param description the user's natural-language meal description
     * @return the named, itemized analysis (items possibly empty)
     */
    MealPhotoAnalyzer.MealAnalysis analyze(String description);

    /**
     * Given the user's description and a short list of candidate saved meals
     * (already name-matched in the catalog), decide which — if any — is the
     * <em>same dish</em> the user is describing. Returns the chosen candidate's
     * {@code mealId}, or empty when none is a genuine match (so the caller
     * creates a new meal rather than logging a near-miss).
     */
    Optional<String> matchMeal(String description, List<MealCandidate> candidates);

    /**
     * A saved-meal candidate offered to {@link #matchMeal} for disambiguation.
     *
     * @param mealId the saved meal's id
     * @param name   its display name, e.g. "Chicken, rice and broccoli"
     */
    record MealCandidate(String mealId, String name) {}
}
