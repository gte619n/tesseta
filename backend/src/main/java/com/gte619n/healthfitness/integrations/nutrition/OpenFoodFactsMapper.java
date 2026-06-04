package com.gte619n.healthfitness.integrations.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.ServingSize;
import java.util.List;
import java.util.Optional;

/**
 * Maps an Open Food Facts product JSON node (as returned by the v2 product API
 * or found one-per-line in the JSONL dump) to a {@link CatalogFood}.
 *
 * <p>Shared by {@link OpenFoodFactsClient} (runtime barcode lookup) and
 * {@link OpenFoodFactsDumpParser} (bulk seeding) so the field reading and unit
 * conventions live in one tested place. Per ADR-0006 the produced food is
 * tagged {@code source = OPEN_FOOD_FACTS}, {@code sourceRef = barcode},
 * {@code status = UNVERIFIED}, with a deterministic {@code "off-" + barcode} id.
 */
final class OpenFoodFactsMapper {

    private OpenFoodFactsMapper() {}

    static Optional<CatalogFood> toCatalogFood(String barcode, JsonNode product) {
        if (barcode == null || barcode.isBlank() || product == null || product.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode nutriments = product.path("nutriments");
        if (nutriments.isMissingNode() || nutriments.isNull() || nutriments.isEmpty()) {
            // Filter to products with a populated nutriments block (ADR-0006).
            return Optional.empty();
        }

        Double calories = firstNumber(nutriments,
            "energy-kcal_100g", "energy-kcal", "energy_100g");
        Double protein = number(nutriments, "proteins_100g");
        Double carbs = number(nutriments, "carbohydrates_100g");
        Double fat = number(nutriments, "fat_100g");
        Double fiber = number(nutriments, "fiber_100g");
        Double sugar = number(nutriments, "sugars_100g");

        // Require at least one usable macro so we don't store empty rows.
        if (calories == null && protein == null && carbs == null && fat == null) {
            return Optional.empty();
        }

        Macros macros = new Macros(calories, protein, carbs, fat, fiber, sugar);

        String name = text(product, "product_name");
        if (name == null || name.isBlank()) {
            name = "Product " + barcode;
        }
        String brand = firstBrand(text(product, "brands"));
        String category = text(product, "categories");

        // Serving sizes: OFF macros are per-100g, but most packaged products
        // declare a real serving (e.g. a 330 ml protein shake). If we only offer
        // "100 g" the user logs the per-100g macros (~9 g protein) instead of the
        // per-serving value (~30 g). When OFF gives us a usable serving_quantity
        // (grams), expose it as the *default* serving so a one-tap log records the
        // serving, not 100 g. Keep "100 g" as a secondary option.
        List<ServingSize> servingSizes = servingSizes(product);

        return Optional.of(new CatalogFood(
            "off-" + barcode,
            name,
            name.toLowerCase(),
            brand,
            barcode,
            category,
            macros,
            servingSizes,
            0,
            FoodSource.OPEN_FOOD_FACTS,
            barcode,
            FoodStatus.UNVERIFIED,
            0,
            null,
            null,
            FoodImageStatus.NONE,
            null,
            null,
            null
        ));
    }

    /**
     * Build the serving list. When OFF declares a positive {@code serving_quantity}
     * (always grams), expose it as the default serving (index 0), labelled with
     * the human {@code serving_size} string when present, and keep "100 g" as a
     * fallback. Otherwise fall back to "100 g" only.
     */
    private static List<ServingSize> servingSizes(JsonNode product) {
        Double servingGrams = number(product, "serving_quantity");
        if (servingGrams == null || servingGrams <= 0) {
            return List.of(new ServingSize("100 g", 100.0));
        }
        String label = text(product, "serving_size");
        if (label == null || label.isBlank()) {
            label = "1 serving";
        }
        return List.of(
            new ServingSize(label, servingGrams),
            new ServingSize("100 g", 100.0)
        );
    }

    /** Open Food Facts "brands" is a comma-separated list; take the first. */
    private static String firstBrand(String brands) {
        if (brands == null || brands.isBlank()) {
            return null;
        }
        int comma = brands.indexOf(',');
        String first = comma >= 0 ? brands.substring(0, comma) : brands;
        first = first.trim();
        return first.isEmpty() ? null : first;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        // OFF values are frequently encoded as strings.
        String s = v.asText(null);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double firstNumber(JsonNode node, String... fields) {
        for (String field : fields) {
            Double v = number(node, field);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
