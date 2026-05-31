package com.gte619n.healthfitness.integrations.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.NutritionLabelAnalyzer.LabelExtraction;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the pure mapping/normalization logic of {@link NutritionLabelExtractor}
 * (the per-serving → per-100 g conversion and tool-arg parsing) without touching
 * the live Gemini client.
 */
class NutritionLabelExtractorTest {

    @Test
    void toPer100g_scalesPerServingByServingSize() {
        // 60 g serving => factor 100/60 ≈ 1.6667
        double factor = 100.0 / 60.0;
        Macros perServing = new Macros(400.0, 33.0, 40.0, 13.0, 8.0, 5.0);
        Macros per100 = NutritionLabelExtractor.toPer100g(perServing, 60.0);
        assertThat(per100.caloriesKcal()).isEqualTo(400.0 * factor);
        assertThat(per100.proteinGrams()).isEqualTo(33.0 * factor);
        assertThat(per100.fiberGrams()).isEqualTo(8.0 * factor);
    }

    @Test
    void toPer100g_with100gServing_isIdentity() {
        Macros perServing = new Macros(250.0, 10.0, 30.0, 9.0, 3.0, 12.0);
        Macros per100 = NutritionLabelExtractor.toPer100g(perServing, 100.0);
        assertThat(per100.caloriesKcal()).isEqualTo(250.0);
        assertThat(per100.carbsGrams()).isEqualTo(30.0);
    }

    @Test
    void toPer100g_missingServingSize_returnsPerServingUnchanged() {
        Macros perServing = new Macros(250.0, 10.0, 30.0, 9.0, 3.0, 12.0);
        assertThat(NutritionLabelExtractor.toPer100g(perServing, null)).isSameAs(perServing);
        assertThat(NutritionLabelExtractor.toPer100g(perServing, 0.0)).isSameAs(perServing);
    }

    @Test
    void toPer100g_nullMacros_returnsNull() {
        assertThat(NutritionLabelExtractor.toPer100g(null, 60.0)).isNull();
    }

    @Test
    void toLabel_parsesToolArgs_andNormalizes() {
        Map<String, Object> macros = orderedMap(
            "caloriesKcal", 200.0,
            "proteinGrams", 10.0,
            "carbsGrams", 20.0,
            "fatGrams", 8.0,
            "fiberGrams", 2.0,
            "sugarGrams", 6.0);
        Map<String, Object> args = orderedMap(
            "productName", "Yogurt",
            "brand", "Chobani",
            "servingSizeGrams", 50.0,
            "servingsPerContainer", 4.0,
            "macrosPerServing", macros);

        LabelExtraction label = NutritionLabelExtractor.toLabel(args);

        assertThat(label.productName()).isEqualTo("Yogurt");
        assertThat(label.brand()).isEqualTo("Chobani");
        assertThat(label.servingSizeGrams()).isEqualTo(50.0);
        assertThat(label.servingsPerContainer()).isEqualTo(4.0);
        // 50 g serving => doubled to per-100g
        assertThat(label.macrosPer100g().caloriesKcal()).isEqualTo(400.0);
        assertThat(label.macrosPer100g().proteinGrams()).isEqualTo(20.0);
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
