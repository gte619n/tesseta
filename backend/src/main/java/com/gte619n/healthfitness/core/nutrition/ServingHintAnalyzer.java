package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;

/**
 * Port for generating a short, human-readable "typical serving" explanation for
 * a logged food or meal — e.g. turning "Blueberries · 92 g" into
 * "About ¾ cup of blueberries". Defined in {@code core} so
 * {@link ServingHintService} can ask for a hint without {@code core} depending on
 * Gemini; the concrete implementation ({@code ServingHintExtractor}) lives in
 * {@code integrations} and is injected via {@code ObjectProvider}, mirroring
 * {@link MealDescriptionAnalyzer}. A no-op/absent bean simply yields no hint.
 */
public interface ServingHintAnalyzer {

    /**
     * Describe {@code grams} of the given food/meal in everyday terms.
     *
     * @param name         the food or meal name (e.g. "Blueberries")
     * @param grams        the logged portion weight in grams, if known
     * @param servingLabel the entry's serving label (e.g. "1 cup"), if any
     * @param components   for a composite meal, its ingredient names; empty for a
     *                     single food
     * @return a one-sentence serving explanation, or empty when unavailable
     */
    Optional<String> describeServing(
        String name, Double grams, String servingLabel, List<String> components);
}
