package com.gte619n.healthfitness.integrations.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.nutrition.MealPhotoAnalyzer.MealItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the pure tool-arg → {@link MealItem} mapping of
 * {@link MealPhotoExtractor} without invoking the live Gemini client.
 */
class MealPhotoExtractorTest {

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
                "fatGrams", 3.6,
                "fiberGrams", 0.0,
                "sugarGrams", 0.0));
        Map<String, Object> rice = orderedMap(
            "name", "White rice",
            "estimatedPortionGrams", 200.0,
            "macrosPer100g", orderedMap(
                "caloriesKcal", 130.0,
                "proteinGrams", 2.7,
                "carbsGrams", 28.0,
                "fatGrams", 0.3));

        List<MealItem> items = MealPhotoExtractor.toItems(
            orderedMap("items", List.of(chicken, rice)));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).name()).isEqualTo("Grilled chicken breast");
        assertThat(items.get(0).estimatedPortionGrams()).isEqualTo(150.0);
        assertThat(items.get(0).confidence()).isEqualTo(0.92);
        assertThat(items.get(0).macrosPer100g().proteinGrams()).isEqualTo(31.0);
        assertThat(items.get(1).name()).isEqualTo("White rice");
        assertThat(items.get(1).macrosPer100g().carbsGrams()).isEqualTo(28.0);
        assertThat(items.get(1).confidence()).isNull();
    }

    @Test
    void toItems_emptyOrMissing_returnsEmptyList() {
        assertThat(MealPhotoExtractor.toItems(Map.of())).isEmpty();
        assertThat(MealPhotoExtractor.toItems(null)).isEmpty();
        assertThat(MealPhotoExtractor.toItems(orderedMap("items", "not-a-list"))).isEmpty();
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
