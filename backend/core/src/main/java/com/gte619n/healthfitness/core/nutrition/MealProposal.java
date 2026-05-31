package com.gte619n.healthfitness.core.nutrition;

import java.util.List;

/**
 * Result of analyzing a full-meal photo: the stored photo reference plus the
 * itemized list of proposed foods. This is a <strong>proposal only</strong> —
 * nothing is persisted as an entry or catalog food. The client reviews/edits and
 * saves via the existing M1 endpoints.
 *
 * @param photoRef storage reference for the uploaded meal photo (nullable when
 *                 storage is unavailable, e.g. core-only test context)
 * @param items    one proposed item per identified food component
 */
public record MealProposal(String photoRef, List<MealProposalItem> items) {

    /**
     * A single proposed meal component, with an optional catalog match.
     *
     * @param name                 human-readable name
     * @param estimatedPortionGrams estimated weight of this component, in grams
     * @param macrosPer100g        macros per 100 g for this food
     * @param confidence           model confidence in {@code [0,1]} (nullable)
     * @param matchedFoodId        id of a matching catalog food, or null on miss
     */
    public record MealProposalItem(
        String name,
        Double estimatedPortionGrams,
        Macros macrosPer100g,
        Double confidence,
        String matchedFoodId
    ) {}
}
