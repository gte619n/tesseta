package com.gte619n.healthfitness.core.nutrition;

import java.util.List;

/**
 * A non-committed "Remove Leftovers" estimate awaiting the user's confirm/discard
 * (IMPL-LEFTOVER-01, spec D7). Stored on the entry's {@link Leftover} while the
 * status is {@link LeftoverStatus#PENDING_REVIEW} so the review screen — and the
 * notification's Apply action (D13) — can render/commit it without the leftover
 * photo (which is discarded after analysis, D11).
 *
 * <p>{@link #items} runs <strong>parallel</strong> to the entry's preserved
 * {@code servedIngredients} (same order and length) so {@code apply} can zip them
 * by index. Each item's {@code consumedGrams} is already clamped to
 * {@code [0, servedGrams]} (spec §4.1). {@link #servedTotals}/{@link #consumedTotals}
 * are the before/after day-total macros for the diff.
 */
public record LeftoverProposal(
    List<Item> items,
    Macros servedTotals,
    Macros consumedTotals,
    double overallConfidence,
    boolean warning,
    String warningNote
) {

    /**
     * One ingredient's consumed estimate.
     *
     * @param name           the ingredient name (matches the served ingredient)
     * @param servedGrams    grams as served (the preserved baseline)
     * @param consumedGrams  grams eaten (clamped to {@code [0, servedGrams]})
     * @param remainingGrams grams left on the plate (informational)
     * @param matched        whether the analyzer mapped a leftover for this item;
     *                        an unmatched served item is treated as fully consumed
     * @param confidence     per-item confidence in {@code [0,1]} (nullable)
     * @param macrosPer100g  the ingredient's frozen per-100 g baseline
     * @param consumedMacros the ingredient's macros at {@code consumedGrams}
     */
    public record Item(
        String name,
        Double servedGrams,
        Double consumedGrams,
        Double remainingGrams,
        boolean matched,
        Double confidence,
        Macros macrosPer100g,
        Macros consumedMacros
    ) {}
}
