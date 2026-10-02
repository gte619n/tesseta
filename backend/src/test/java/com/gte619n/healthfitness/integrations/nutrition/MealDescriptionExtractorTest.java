package com.gte619n.healthfitness.integrations.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gte619n.healthfitness.core.nutrition.MealPhotoAnalyzer.MealAnalysis;
import com.gte619n.healthfitness.core.nutrition.MealPhotoAnalyzer.MealItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the pure tool-arg → {@link MealItem} mapping of
 * {@link MealDescriptionExtractor} (the text-input sibling of
 * {@code MealPhotoExtractor}) without invoking the live Gemini client.
 */
class MealDescriptionExtractorTest {

    @Test
    void toItems_mapsEachComponentWithPer100gMacros() {
        Map<String, Object> chicken = orderedMap(
            "name", "Grilled chicken breast",
            "estimatedPortionGrams", 150.0,
            "confidence", 0.92,
            "macrosPer100g", orderedMap(
                "caloriesKcal", 165.0,
                "proteinGrams", 31.0,
                "carbsGrams", 0.0,
                "fatGrams", 3.6));
        Map<String, Object> broccoli = orderedMap(
            "name", "Steamed broccoli",
            "estimatedPortionGrams", 100.0,
            "macrosPer100g", orderedMap(
                "caloriesKcal", 35.0,
                "proteinGrams", 2.4,
                "carbsGrams", 7.0,
                "fatGrams", 0.4));

        List<MealItem> items = MealDescriptionExtractor.toItems(
            orderedMap("items", List.of(chicken, broccoli)));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).name()).isEqualTo("Grilled chicken breast");
        assertThat(items.get(0).estimatedPortionGrams()).isEqualTo(150.0);
        assertThat(items.get(0).macrosPer100g().proteinGrams()).isEqualTo(31.0);
        assertThat(items.get(1).name()).isEqualTo("Steamed broccoli");
        assertThat(items.get(1).confidence()).isNull();
    }

    @Test
    void toAnalysis_extractsMealNameAndPackagedFlag() {
        Map<String, Object> args = orderedMap(
            "mealName", "Chicken, rice and broccoli",
            "isPackagedProduct", false,
            "items", List.of(orderedMap(
                "name", "Grilled chicken breast",
                "estimatedPortionGrams", 150.0,
                "macrosPer100g", orderedMap("caloriesKcal", 165.0, "proteinGrams", 31.0))));

        MealAnalysis analysis = MealDescriptionExtractor.toAnalysis(args);

        assertThat(analysis.mealName()).isEqualTo("Chicken, rice and broccoli");
        assertThat(analysis.packagedProduct()).isFalse();
        assertThat(analysis.items()).hasSize(1);
    }

    @Test
    void toAnalysis_packagedProduct_isFlagged_blankNameBecomesNull() {
        Map<String, Object> args = orderedMap(
            "mealName", "  ",
            "isPackagedProduct", true,
            "items", List.of(orderedMap("name", "Chocolate protein shake", "estimatedPortionGrams", 330.0)));

        MealAnalysis analysis = MealDescriptionExtractor.toAnalysis(args);

        assertThat(analysis.packagedProduct()).isTrue();
        assertThat(analysis.mealName()).isNull();
        assertThat(analysis.items()).hasSize(1);
    }

    @Test
    void toItems_derivesAlcoholFromAbv_whenGramsOmitted() {
        // A neat spirit: the model gives ABV% but omits alcoholGrams (common), and
        // has no mixer macros. Without the ABV fallback this logs as 0 kcal and
        // never routes to Drinks — the exact bug. 40% ABV → 40×0.789 = 31.56 g/100g,
        // ×7 = 220.9 kcal/100g.
        Map<String, Object> gin = orderedMap(
            "name", "Gin",
            "estimatedPortionGrams", 44.0,
            "macrosPer100g", orderedMap(
                "caloriesKcal", 0.0,
                "proteinGrams", 0.0,
                "carbsGrams", 0.0,
                "fatGrams", 0.0,
                "abvPercent", 40.0));

        List<MealItem> items = MealDescriptionExtractor.toItems(orderedMap("items", List.of(gin)));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).macrosPer100g().alcoholGrams()).isCloseTo(31.56, within(0.01));
        assertThat(items.get(0).macrosPer100g().caloriesKcal()).isCloseTo(220.92, within(0.1));
    }

    @Test
    void toItems_prefersExplicitAlcoholGrams_overAbv() {
        // When the model does report grams of ethanol directly, trust it rather
        // than re-deriving from ABV.
        Map<String, Object> wine = orderedMap(
            "name", "Red wine",
            "estimatedPortionGrams", 150.0,
            "macrosPer100g", orderedMap(
                "proteinGrams", 0.0,
                "carbsGrams", 2.6,
                "fatGrams", 0.0,
                "abvPercent", 13.0,
                "alcoholGrams", 10.0));

        List<MealItem> items = MealDescriptionExtractor.toItems(orderedMap("items", List.of(wine)));

        assertThat(items.get(0).macrosPer100g().alcoholGrams()).isEqualTo(10.0);
        assertThat(items.get(0).macrosPer100g().caloriesKcal()).isCloseTo(80.4, within(0.1));
    }

    @Test
    void toItems_noAlcoholSignal_leavesAlcoholUnset() {
        // A non-alcoholic food (no abvPercent, no alcoholGrams) must not be tagged
        // alcoholic — otherwise it would wrongly auto-route to the Drinks meal.
        Map<String, Object> soda = orderedMap(
            "name", "Club soda",
            "estimatedPortionGrams", 350.0,
            "macrosPer100g", orderedMap(
                "caloriesKcal", 0.0,
                "proteinGrams", 0.0,
                "carbsGrams", 0.0,
                "fatGrams", 0.0));

        List<MealItem> items = MealDescriptionExtractor.toItems(orderedMap("items", List.of(soda)));

        assertThat(items.get(0).macrosPer100g().alcoholGrams()).isNull();
    }

    @Test
    void toItems_emptyOrMissing_returnsEmptyList() {
        assertThat(MealDescriptionExtractor.toItems(Map.of())).isEmpty();
        assertThat(MealDescriptionExtractor.toItems(null)).isEmpty();
        assertThat(MealDescriptionExtractor.toItems(orderedMap("items", "not-a-list"))).isEmpty();
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
