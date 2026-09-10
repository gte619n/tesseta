package com.gte619n.healthfitness.core.nutrition;

/**
 * Port for turning a free-text drink name ("Negroni", "vodka soda", "6oz malbec")
 * into its estimated alcohol facts and per-serving mixer macros (IMPL-DRINK-01,
 * D10). The concrete implementation ({@code DrinkExtractor}, Gemini flash tool
 * calling) lives in {@code integrations} and is injected via {@code ObjectProvider}
 * so core unit tests construct the service without it.
 *
 * <p>The analyzer estimates ABV% and serving volume; the backend
 * ({@link DrinkMath}) — not the model — derives alcohol grams, standard drinks and
 * calories, keeping the arithmetic deterministic and auditable. On an extraction
 * failure the implementation throws.
 */
public interface DrinkAnalyzer {

    /**
     * Estimate the drink's facts from its name.
     *
     * @param name the drink's name / short description
     * @return the estimated analysis
     */
    DrinkAnalysis analyze(String name);

    /**
     * The estimated facts for a single serving of a drink.
     *
     * @param name            a cleaned display name (e.g. "Negroni")
     * @param abvPercent      alcohol by volume, percent
     * @param servingVolumeMl the typical serving volume in millilitres
     * @param macros          per-serving mixer/sugar macros (protein/carbs/fat/
     *                        fiber/sugar), EXCLUDING alcohol calories; may be a
     *                        zero bundle for a neat spirit
     */
    record DrinkAnalysis(
        String name,
        Double abvPercent,
        Double servingVolumeMl,
        Macros macros
    ) {}
}
