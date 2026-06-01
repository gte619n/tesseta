package com.gte619n.healthfitness.integrations.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenFoodFactsDumpParserTest {

    private final OpenFoodFactsDumpParser parser = new OpenFoodFactsDumpParser();

    // Three JSONL lines: two valid products, one with an empty nutriments block
    // that must be filtered out per ADR-0006.
    private static final String JSONL =
        "{\"code\":\"3017620422003\",\"product_name\":\"Nutella\",\"brands\":\"Ferrero, Nutella\","
            + "\"categories\":\"Spreads\",\"nutriments\":{\"energy-kcal_100g\":539,"
            + "\"proteins_100g\":6.3,\"carbohydrates_100g\":57.5,\"fat_100g\":30.9,"
            + "\"fiber_100g\":0,\"sugars_100g\":56.3,\"salt_100g\":\"0.107\"}}\n"
        + "{\"code\":\"5449000000996\",\"product_name\":\"Coca-Cola\",\"brands\":\"Coca-Cola\","
            + "\"nutriments\":{\"energy-kcal_100g\":\"42\",\"proteins_100g\":0,"
            + "\"carbohydrates_100g\":10.6,\"fat_100g\":0,\"sugars_100g\":10.6}}\n"
        + "{\"code\":\"0000000000000\",\"product_name\":\"Empty\",\"nutriments\":{}}\n";

    @Test
    void parsesValidProductsAndFiltersEmptyNutriments() {
        List<CatalogFood> foods = collect(JSONL);

        assertThat(foods).hasSize(2);

        CatalogFood nutella = foods.get(0);
        assertThat(nutella.foodId()).isEqualTo("off-3017620422003");
        assertThat(nutella.barcode()).isEqualTo("3017620422003");
        assertThat(nutella.sourceRef()).isEqualTo("3017620422003");
        assertThat(nutella.source()).isEqualTo(FoodSource.OPEN_FOOD_FACTS);
        assertThat(nutella.status()).isEqualTo(FoodStatus.UNVERIFIED);
        assertThat(nutella.imageStatus()).isEqualTo(FoodImageStatus.NONE);
        assertThat(nutella.name()).isEqualTo("Nutella");
        assertThat(nutella.nameLower()).isEqualTo("nutella");
        // brands is a comma list — first wins.
        assertThat(nutella.brand()).isEqualTo("Ferrero");
        assertThat(nutella.macrosPer100g().caloriesKcal()).isEqualTo(539.0);
        assertThat(nutella.macrosPer100g().sugarGrams()).isCloseTo(56.3, within(0.001));
        assertThat(nutella.servingSizes()).singleElement()
            .satisfies(s -> assertThat(s.grams()).isEqualTo(100.0));

        CatalogFood coke = foods.get(1);
        assertThat(coke.foodId()).isEqualTo("off-5449000000996");
        // calories provided as a string — still parsed.
        assertThat(coke.macrosPer100g().caloriesKcal()).isEqualTo(42.0);
        assertThat(coke.macrosPer100g().carbsGrams()).isCloseTo(10.6, within(0.001));
    }

    @Test
    void deterministicIdsAreIdempotent() {
        assertThat(collect(JSONL).get(0).foodId())
            .isEqualTo(collect(JSONL).get(0).foodId());
    }

    @Test
    void skipsBlankAndUnparseableLines() {
        String input = "\nnot-json\n" + JSONL;
        assertThat(collect(input)).hasSize(2);
    }

    private List<CatalogFood> collect(String jsonl) {
        List<CatalogFood> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(jsonl))) {
            long n = parser.parse(reader, out::add);
            assertThat(n).isEqualTo(out.size());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
