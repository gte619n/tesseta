package com.gte619n.healthfitness.core.nutrition;

/**
 * One ingredient within a composite meal entry — e.g. "Salmon" inside a
 * "Salmon, Rice &amp; Broccolini" meal logged from a photo.
 *
 * <p>{@code foodId} links the catalog food that holds the generated
 * <em>raw-ingredient</em> studio image. {@code macrosPer100g} is the frozen
 * baseline used to re-scale {@code macros} when the user edits the portion, the
 * same maths the add flow uses for single foods.
 */
public record CompositeIngredient(
    String name,
    String foodId,
    Macros macrosPer100g,
    Double servingGrams,
    String servingLabel,
    Double quantity,
    Macros macros
) {
    /** Returns a copy re-scaled to a new portion from the per-100g baseline. */
    public CompositeIngredient withPortion(
        Double newServingGrams, String newServingLabel, Double newQuantity) {
        double grams = newServingGrams != null
            ? newServingGrams
            : (servingGrams != null ? servingGrams : 0.0);
        double qty = newQuantity != null
            ? newQuantity
            : (quantity != null ? quantity : 1.0);
        double factor = (grams * qty) / 100.0;
        Macros scaled = macrosPer100g != null ? macrosPer100g.scale(factor) : macros;
        return new CompositeIngredient(
            name,
            foodId,
            macrosPer100g,
            grams,
            newServingLabel != null ? newServingLabel : servingLabel,
            qty,
            scaled);
    }
}
