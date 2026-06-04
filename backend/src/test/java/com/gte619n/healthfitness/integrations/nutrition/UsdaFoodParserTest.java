package com.gte619n.healthfitness.integrations.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsdaFoodParserTest {

    private final UsdaFoodParser parser = new UsdaFoodParser();

    private static final String CSV = """
        fdc_id,description,food_category,energy_kcal,protein_g,carbs_g,fat_g,fiber_g,sugar_g
        171077,"Chicken, broilers or fryers, breast, meat only, cooked, grilled",Poultry,165,31.0,0,3.6,0,0
        169704,"Rice, brown, long-grain, cooked",Cereal Grains,123,2.74,25.6,0.97,1.6,0.24
        """;

    @Test
    void parsesUsdaRowsToCatalogFoods() {
        List<CatalogFood> foods = collect(CSV);

        assertThat(foods).hasSize(2);

        CatalogFood chicken = foods.get(0);
        assertThat(chicken.foodId()).isEqualTo("usda-171077");
        assertThat(chicken.sourceRef()).isEqualTo("usda-171077");
        assertThat(chicken.source()).isEqualTo(FoodSource.USDA);
        assertThat(chicken.status()).isEqualTo(FoodStatus.VERIFIED);
        assertThat(chicken.name()).startsWith("Chicken, broilers");
        assertThat(chicken.nameLower()).isEqualTo(chicken.name().toLowerCase());
        assertThat(chicken.category()).isEqualTo("Poultry");
        assertThat(chicken.macrosPer100g().caloriesKcal()).isEqualTo(165.0);
        assertThat(chicken.macrosPer100g().proteinGrams()).isCloseTo(31.0, within(0.001));
        assertThat(chicken.macrosPer100g().fatGrams()).isCloseTo(3.6, within(0.001));
        assertThat(chicken.servingSizes()).singleElement()
            .satisfies(s -> {
                assertThat(s.label()).isEqualTo("100 g");
                assertThat(s.grams()).isEqualTo(100.0);
            });

        CatalogFood rice = foods.get(1);
        assertThat(rice.foodId()).isEqualTo("usda-169704");
        assertThat(rice.macrosPer100g().carbsGrams()).isCloseTo(25.6, within(0.001));
        assertThat(rice.macrosPer100g().fiberGrams()).isCloseTo(1.6, within(0.001));
        assertThat(rice.macrosPer100g().sugarGrams()).isCloseTo(0.24, within(0.001));
    }

    @Test
    void deterministicIdsMakeSeedingIdempotent() {
        List<CatalogFood> first = collect(CSV);
        List<CatalogFood> second = collect(CSV);
        assertThat(first.get(0).foodId()).isEqualTo(second.get(0).foodId());
        assertThat(first.get(1).foodId()).isEqualTo(second.get(1).foodId());
    }

    @Test
    void handlesAbbreviatedSrLegacyColumnNames() {
        String csv = """
            NDB_No,Shrt_Desc,Energ_Kcal,Protein,Carbohydrt,Lipid_Tot,Fiber_TD,Sugar_Tot
            01001,"BUTTER,WITH SALT",717,0.85,0.06,81.11,0,0.06
            """;
        List<CatalogFood> foods = collect(csv);
        assertThat(foods).hasSize(1);
        CatalogFood butter = foods.get(0);
        assertThat(butter.foodId()).isEqualTo("usda-01001");
        assertThat(butter.macrosPer100g().caloriesKcal()).isEqualTo(717.0);
        assertThat(butter.macrosPer100g().fatGrams()).isCloseTo(81.11, within(0.001));
    }

    private List<CatalogFood> collect(String csv) {
        List<CatalogFood> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            long n = parser.parse(reader, out::add);
            assertThat(n).isEqualTo(out.size());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
