package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealProposal;

/**
 * Wire representation of one proposed meal-photo item. Carries both per-100 g
 * macros and the macros scaled to the estimated portion, plus a suggested
 * serving label and any catalog match.
 */
public record MealItemDto(
    String name,
    Double estimatedPortionGrams,
    String suggestedServingLabel,
    MacrosDto macrosPer100g,
    MacrosDto macrosForPortion,
    Double confidence,
    String matchedFoodId
) {
    public static MealItemDto from(MealProposal.MealProposalItem item) {
        Double grams = item.estimatedPortionGrams();
        Macros per100g = item.macrosPer100g();
        Macros forPortion = (per100g != null && grams != null)
            ? per100g.scale(grams / 100.0)
            : null;
        return new MealItemDto(
            item.name(),
            grams,
            suggestedServingLabel(grams),
            MacrosDto.from(per100g),
            MacrosDto.from(forPortion),
            item.confidence(),
            item.matchedFoodId()
        );
    }

    private static String suggestedServingLabel(Double grams) {
        if (grams == null) {
            return null;
        }
        return Math.round(grams) + " g";
    }
}
