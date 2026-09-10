package com.gte619n.healthfitness.core.nutrition;

/**
 * Alcohol facts for a drink (IMPL-DRINK-01). Present only on {@link CatalogFood}s
 * whose {@code category} is {@code "drink"}; null for all other foods.
 *
 * <p>{@code abvPercent} and {@code servingVolumeMl} are the inputs (from AI or the
 * user); {@code alcoholGrams} and {@code standardDrinks} are derived by
 * {@link DrinkMath} and stored so clients never re-derive. All values describe the
 * drink's DEFAULT serving — a logged entry at quantity {@code q} multiplies the
 * displayed standard-drink count by {@code q} client-side.
 */
public record AlcoholInfo(
    Double abvPercent,
    Double servingVolumeMl,
    Double alcoholGrams,
    Double standardDrinks
) {}
