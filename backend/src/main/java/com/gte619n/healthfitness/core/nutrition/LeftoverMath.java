package com.gte619n.healthfitness.core.nutrition;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure consumed-portion math for "Remove Leftovers" (IMPL-LEFTOVER-01, spec §4.1
 * / §4.2). Given the meal's preserved as-served ingredients and the analyzer's
 * remaining-per-item estimate, it either <em>rejects</em> the pass (retake) or
 * produces a clamped {@link LeftoverProposal}.
 *
 * <p>No I/O, no Gemini — every rule here is deterministic and unit-testable
 * (spec §6.3 mocks the analyzer and asserts this math):
 * <ul>
 *   <li><b>Reject</b> when the analyzer recognized nothing (empty / no served
 *       item maps to any estimate) or overall confidence is below
 *       {@link #MIN_CONFIDENCE} (spec D12).</li>
 *   <li><b>Clamp</b> each item's consumed grams to {@code [0, served]} — never
 *       negative, never "ate more than served".</li>
 *   <li><b>Unmatched</b> served items (the analyzer found no leftover for them)
 *       are treated as fully consumed, with a warning note.</li>
 * </ul>
 */
public final class LeftoverMath {

    /** Below this overall confidence the pass is rejected and the user retakes (spec D12). */
    public static final double MIN_CONFIDENCE = 0.35;

    private LeftoverMath() {}

    /** Either a rejection (with a reason) or a computed proposal. */
    public record Result(boolean rejected, String rejectReason, LeftoverProposal proposal) {
        static Result reject(String reason) {
            return new Result(true, reason, null);
        }

        static Result ok(LeftoverProposal proposal) {
            return new Result(false, null, proposal);
        }
    }

    /**
     * Compute the consumed proposal from the served baseline and the estimate.
     *
     * @param served   the preserved as-served ingredients (the baseline)
     * @param estimate the analyzer's remaining-per-item estimate (nullable)
     */
    public static Result compute(
        List<CompositeIngredient> served, LeftoverAnalyzer.LeftoverEstimate estimate) {
        if (served == null || served.isEmpty()) {
            return Result.reject("no served ingredients to subtract from");
        }
        if (estimate == null || estimate.items() == null || estimate.items().isEmpty()) {
            return Result.reject("could not read the leftovers");
        }

        int matchedCount = 0;
        boolean warning = false;
        List<LeftoverProposal.Item> items = new ArrayList<>(served.size());
        Macros servedTotals = Macros.zero();
        Macros consumedTotals = Macros.zero();

        for (CompositeIngredient ing : served) {
            double servedGrams = effectiveGrams(ing);
            LeftoverAnalyzer.LeftoverEstimate.ItemEstimate est = matchEstimate(estimate, ing.name());

            double remaining;
            boolean matched;
            Double confidence;
            if (est != null && est.matched() && est.remainingGrams() != null) {
                matched = true;
                matchedCount++;
                confidence = est.confidence();
                remaining = clamp(est.remainingGrams(), 0.0, servedGrams);
            } else {
                // Unmatched served item → assume it was fully eaten (nothing left),
                // and flag the pass so the review surfaces the assumption (spec §4.2).
                matched = false;
                confidence = null;
                remaining = 0.0;
                warning = true;
            }
            double consumedGrams = clamp(servedGrams - remaining, 0.0, servedGrams);

            CompositeIngredient consumedIng =
                ing.withPortion(consumedGrams, gramsLabel(consumedGrams), 1.0);
            Macros consumedMacros = consumedIng.macros() != null
                ? consumedIng.macros().withDerivedCalories() : Macros.zero();

            servedTotals = servedTotals.plus(ing.macros());
            consumedTotals = consumedTotals.plus(consumedMacros);

            items.add(new LeftoverProposal.Item(
                ing.name(),
                servedGrams,
                consumedGrams,
                remaining,
                matched,
                confidence,
                ing.macrosPer100g(),
                consumedMacros));
        }

        if (matchedCount == 0) {
            // Nothing in the leftover photo mapped to the meal — hard mismatch.
            return Result.reject("the leftovers don't match this meal");
        }
        if (estimate.overallConfidence() < MIN_CONFIDENCE) {
            return Result.reject("not confident enough about the leftovers");
        }

        String note = warning
            ? "Some items weren't visible in the leftover photo and were counted as fully eaten."
            : null;
        LeftoverProposal proposal = new LeftoverProposal(
            items,
            servedTotals.withDerivedCalories(),
            consumedTotals.withDerivedCalories(),
            estimate.overallConfidence(),
            warning,
            note);
        return Result.ok(proposal);
    }

    /**
     * Rebuild the consumed ingredient list from the preserved served baseline and
     * an accepted proposal, zipping by index (spec §4.1). Used by {@code apply}.
     */
    public static List<CompositeIngredient> consumedIngredients(
        List<CompositeIngredient> served, LeftoverProposal proposal) {
        List<CompositeIngredient> out = new ArrayList<>(served.size());
        for (int i = 0; i < served.size(); i++) {
            CompositeIngredient ing = served.get(i);
            double consumedGrams = i < proposal.items().size()
                    && proposal.items().get(i).consumedGrams() != null
                ? proposal.items().get(i).consumedGrams()
                : effectiveGrams(ing);
            out.add(ing.withPortion(consumedGrams, gramsLabel(consumedGrams), 1.0));
        }
        return out;
    }

    /** The ingredient's as-served weight, folding its quantity into grams. */
    static double effectiveGrams(CompositeIngredient ing) {
        double grams = ing.servingGrams() != null ? ing.servingGrams() : 0.0;
        double qty = ing.quantity() != null ? ing.quantity() : 1.0;
        return grams * qty;
    }

    private static LeftoverAnalyzer.LeftoverEstimate.ItemEstimate matchEstimate(
        LeftoverAnalyzer.LeftoverEstimate estimate, String name) {
        if (name == null) {
            return null;
        }
        for (LeftoverAnalyzer.LeftoverEstimate.ItemEstimate it : estimate.items()) {
            if (it != null && it.name() != null && it.name().equalsIgnoreCase(name)) {
                return it;
            }
        }
        return null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String gramsLabel(double grams) {
        return Math.round(grams) + " g";
    }
}
