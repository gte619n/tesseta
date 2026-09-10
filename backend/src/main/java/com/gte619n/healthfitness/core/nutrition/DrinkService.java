package com.gte619n.healthfitness.core.nutrition;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * IMPL-DRINK-01 drink-analysis orchestration: run the {@link DrinkAnalyzer} to
 * turn a drink name into a review-ready proposal, deriving the alcohol maths
 * ({@link DrinkMath}) so the arithmetic is deterministic and the client can show
 * (and edit) exact numbers before saving (D10/D11).
 *
 * <p>The analyzer is injected via {@link ObjectProvider} so core tests and
 * capture-disabled contexts run without the live Gemini bean — {@link #analyze}
 * throws {@link IllegalStateException} when it is unavailable, which the controller
 * maps to 422, matching the meal-description flow.
 */
@Service
public class DrinkService {

    private final ObjectProvider<DrinkAnalyzer> analyzer;

    public DrinkService(ObjectProvider<DrinkAnalyzer> analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * Analyze a drink name into a review-ready proposal. Never persists anything.
     *
     * @throws IllegalStateException when the analyzer is unavailable
     */
    public DrinkProposal analyze(String name) {
        DrinkAnalyzer a = analyzer.getIfAvailable();
        if (a == null) {
            throw new IllegalStateException("drink analysis is unavailable");
        }
        DrinkAnalyzer.DrinkAnalysis analysis = a.analyze(name);
        return proposalFrom(
            analysis.name(),
            analysis.abvPercent(),
            analysis.servingVolumeMl(),
            analysis.macros());
    }

    /**
     * Build a proposal (derived alcohol facts + total serving calories) from raw
     * inputs — used both for a fresh AI analysis and to recompute after the user
     * edits a field. {@code macroContribution} is the mixer/sugar macros for the
     * serving, excluding alcohol calories.
     */
    public DrinkProposal proposalFrom(
        String name, Double abvPercent, Double servingVolumeMl, Macros macroContribution) {
        AlcoholInfo alcohol = DrinkMath.describe(abvPercent, servingVolumeMl);
        double grams = alcohol.alcoholGrams() != null ? alcohol.alcoholGrams() : 0.0;
        double calories = DrinkMath.servingCalories(macroContribution, grams);
        Macros serving = new Macros(
            calories,
            macroContribution != null ? macroContribution.proteinGrams() : null,
            macroContribution != null ? macroContribution.carbsGrams() : null,
            macroContribution != null ? macroContribution.fatGrams() : null,
            macroContribution != null ? macroContribution.fiberGrams() : null,
            macroContribution != null ? macroContribution.sugarGrams() : null);
        return new DrinkProposal(name, abvPercent, servingVolumeMl, serving, alcohol);
    }

    /**
     * A review-ready drink: the (possibly AI-estimated) inputs plus the derived
     * per-serving macros (with total calories including alcohol) and alcohol facts.
     */
    public record DrinkProposal(
        String name,
        Double abvPercent,
        Double servingVolumeMl,
        Macros servingMacros,
        AlcoholInfo alcohol
    ) {}
}
