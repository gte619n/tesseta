package com.gte619n.healthfitness.core.nutrition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final FoodCatalogService catalog;
    private final ObjectProvider<DrinkOrderRepository> orderRepo;

    public DrinkService(
        ObjectProvider<DrinkAnalyzer> analyzer,
        FoodCatalogService catalog,
        ObjectProvider<DrinkOrderRepository> orderRepo
    ) {
        this.analyzer = analyzer;
        this.catalog = catalog;
        this.orderRepo = orderRepo;
    }

    /**
     * My non-archived drinks in the user's saved display order (IMPL-DRINK-01
     * reorder): drinks whose id appears in the saved order come first, in that
     * order; any not yet ordered (e.g. just-created) follow in the catalog's
     * default newest-first order. The Android card renders whatever order this
     * returns (it preserves server order in its local cache).
     */
    public List<CatalogFood> listOrderedDrinks(String userId) {
        List<CatalogFood> drinks = catalog.listMyDrinks(userId);
        DrinkOrderRepository repo = orderRepo.getIfAvailable();
        if (repo == null) {
            return drinks;
        }
        List<String> order = repo.getOrder(userId);
        if (order == null || order.isEmpty()) {
            return drinks;
        }
        Map<String, CatalogFood> byId = new LinkedHashMap<>();
        for (CatalogFood d : drinks) {
            byId.put(d.foodId(), d);
        }
        List<CatalogFood> ordered = new ArrayList<>(drinks.size());
        for (String id : order) {
            CatalogFood d = byId.remove(id);
            if (d != null) {
                ordered.add(d);
            }
        }
        // Remaining (unordered / newly added) keep their default order.
        ordered.addAll(byId.values());
        return ordered;
    }

    /** Persist the user's drink display order (list of drink foodIds). */
    public void saveOrder(String userId, List<String> orderedDrinkIds) {
        DrinkOrderRepository repo = orderRepo.getIfAvailable();
        if (repo != null) {
            repo.saveOrder(userId, orderedDrinkIds == null ? List.of() : orderedDrinkIds);
        }
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
