package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.nutrition.LeftoverAnalyzer.LeftoverEstimate;
import com.gte619n.healthfitness.core.nutrition.LeftoverAnalyzer.LeftoverEstimate.ItemEstimate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for {@link LeftoverMath} — the consumed-portion math
 * and reject/clamp classifier (IMPL-LEFTOVER-01 spec §4.1/§4.2, §6.1). No Gemini.
 */
class LeftoverMathTest {

    // rice per 100 g: 2.7 P / 28 C / 0.3 F → derived 125.5 kcal
    private static final Macros RICE_100 = new Macros(null, 2.7, 28.0, 0.3, 0.4, 0.1);
    // salmon per 100 g: 20 P / 0 C / 13 F → derived 197 kcal
    private static final Macros SALMON_100 = new Macros(null, 20.0, 0.0, 13.0, 0.0, 0.0);

    private static CompositeIngredient ing(String name, Macros per100, double grams) {
        return new CompositeIngredient(
            name, "food-" + name, per100, grams, grams + " g", 1.0, per100.scale(grams / 100.0));
    }

    private static List<CompositeIngredient> servedMeal() {
        return List.of(ing("rice", RICE_100, 150.0), ing("salmon", SALMON_100, 140.0));
    }

    @Test
    void computesConsumedPerIngredient_clampedAndSummed() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("rice", 75.0, true, 0.9),    // ate half the rice
            new ItemEstimate("salmon", 20.0, true, 0.9)), // ate 120 of 140 g salmon
            0.9);

        LeftoverMath.Result r = LeftoverMath.compute(servedMeal(), est);

        assertFalse(r.rejected());
        LeftoverProposal p = r.proposal();
        assertEquals(2, p.items().size());
        assertEquals(150.0, p.items().get(0).servedGrams(), 1e-6);
        assertEquals(75.0, p.items().get(0).consumedGrams(), 1e-6);
        assertEquals(140.0, p.items().get(1).servedGrams(), 1e-6);
        assertEquals(120.0, p.items().get(1).consumedGrams(), 1e-6);

        double servedKcal = RICE_100.scale(1.5).withDerivedCalories().caloriesKcal()
            + SALMON_100.scale(1.4).withDerivedCalories().caloriesKcal();
        double consumedKcal = RICE_100.scale(0.75).withDerivedCalories().caloriesKcal()
            + SALMON_100.scale(1.2).withDerivedCalories().caloriesKcal();
        assertEquals(servedKcal, p.servedTotals().caloriesKcal(), 1e-6);
        assertEquals(consumedKcal, p.consumedTotals().caloriesKcal(), 1e-6);
        assertTrue(p.consumedTotals().caloriesKcal() < p.servedTotals().caloriesKcal());
    }

    @Test
    void nothingLeft_consumedEqualsServed() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("rice", 0.0, true, 0.9),
            new ItemEstimate("salmon", 0.0, true, 0.9)), 0.9);

        LeftoverProposal p = LeftoverMath.compute(servedMeal(), est).proposal();

        assertEquals(150.0, p.items().get(0).consumedGrams(), 1e-6);
        assertEquals(140.0, p.items().get(1).consumedGrams(), 1e-6);
        assertEquals(p.servedTotals().caloriesKcal(), p.consumedTotals().caloriesKcal(), 1e-6);
    }

    @Test
    void allLeft_consumedZero() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("rice", 150.0, true, 0.9),
            new ItemEstimate("salmon", 140.0, true, 0.9)), 0.9);

        LeftoverProposal p = LeftoverMath.compute(servedMeal(), est).proposal();

        assertEquals(0.0, p.items().get(0).consumedGrams(), 1e-6);
        assertEquals(0.0, p.items().get(1).consumedGrams(), 1e-6);
        assertEquals(0.0, p.consumedTotals().caloriesKcal(), 1e-6);
    }

    @Test
    void remainingExceedingServed_clampsToServed_noNegatives() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("rice", 999.0, true, 0.9),   // absurd: more than served
            new ItemEstimate("salmon", 200.0, true, 0.9)), 0.9);

        LeftoverProposal p = LeftoverMath.compute(servedMeal(), est).proposal();

        assertEquals(0.0, p.items().get(0).consumedGrams(), 1e-6);
        assertEquals(150.0, p.items().get(0).remainingGrams(), 1e-6); // clamped to served
        assertEquals(0.0, p.items().get(1).consumedGrams(), 1e-6);
        assertTrue(p.consumedTotals().caloriesKcal() >= 0.0);
        for (LeftoverProposal.Item it : p.items()) {
            assertTrue(it.consumedMacros().proteinGrams() >= 0.0);
        }
    }

    @Test
    void unmatchedServedItem_countedFullyConsumed_withWarning() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("rice", 75.0, true, 0.9)),   // salmon not reported
            0.9);

        LeftoverMath.Result r = LeftoverMath.compute(servedMeal(), est);
        LeftoverProposal p = r.proposal();

        assertFalse(r.rejected());
        assertTrue(p.warning());
        assertEquals(75.0, p.items().get(0).consumedGrams(), 1e-6);
        assertFalse(p.items().get(1).matched());
        assertEquals(140.0, p.items().get(1).consumedGrams(), 1e-6, "unmatched = fully eaten");
    }

    @Test
    void emptyEstimate_rejected() {
        LeftoverMath.Result r = LeftoverMath.compute(servedMeal(), new LeftoverEstimate(List.of(), 0.9));
        assertTrue(r.rejected());
        assertNull(r.proposal());
    }

    @Test
    void noMatchingItems_rejectedAsMismatch() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("pizza", 100.0, true, 0.9)), 0.9); // foreign item
        LeftoverMath.Result r = LeftoverMath.compute(servedMeal(), est);
        assertTrue(r.rejected());
    }

    @Test
    void lowConfidence_rejected() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("rice", 75.0, true, 0.2),
            new ItemEstimate("salmon", 20.0, true, 0.2)),
            LeftoverMath.MIN_CONFIDENCE - 0.01);
        LeftoverMath.Result r = LeftoverMath.compute(servedMeal(), est);
        assertTrue(r.rejected());
    }

    @Test
    void consumedIngredients_zipsProposalOntoServedBaseline() {
        LeftoverEstimate est = new LeftoverEstimate(List.of(
            new ItemEstimate("rice", 75.0, true, 0.9),
            new ItemEstimate("salmon", 20.0, true, 0.9)), 0.9);
        LeftoverProposal p = LeftoverMath.compute(servedMeal(), est).proposal();

        List<CompositeIngredient> consumed = LeftoverMath.consumedIngredients(servedMeal(), p);

        assertEquals(75.0, consumed.get(0).servingGrams(), 1e-6);
        assertEquals(120.0, consumed.get(1).servingGrams(), 1e-6);
        assertEquals(1.0, consumed.get(0).quantity(), 1e-6);
    }

    @Test
    void emptyServed_rejected() {
        LeftoverMath.Result r = LeftoverMath.compute(List.of(),
            new LeftoverEstimate(List.of(new ItemEstimate("rice", 10.0, true, 0.9)), 0.9));
        assertTrue(r.rejected());
    }
}
