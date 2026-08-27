package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link FoodImageCacheKey}: subjects that produce the same generated
 * image collapse to one key (so the image is reused) while ones that would render
 * differently stay distinct.
 */
class FoodImageCacheKeyTest {

    @Test
    void sameSubject_differingOnlyByCaseAndWhitespace_sharesKey() {
        assertEquals(
            FoodImageCacheKey.of(food("Grilled  Chicken", "Protein", null)),
            FoodImageCacheKey.of(food("grilled chicken", "protein", null)));
    }

    @Test
    void differentCategory_differentKey() {
        // Category drives the style bucket (raw ingredient vs plated dish), so an
        // "ingredient" banana must not reuse a "dish" banana's image.
        assertNotEquals(
            FoodImageCacheKey.of(food("Banana", "ingredient", null)),
            FoodImageCacheKey.of(food("Banana", "dish", null)));
    }

    @Test
    void differentBrand_differentKey() {
        assertNotEquals(
            FoodImageCacheKey.of(food("Protein Bar", "product", "Brand A")),
            FoodImageCacheKey.of(food("Protein Bar", "product", "Brand B")));
    }

    @Test
    void nullFields_areStableAndDoNotThrow() {
        assertEquals(
            FoodImageCacheKey.of(food("Chicken bowl", null, null)),
            FoodImageCacheKey.of(food("chicken bowl", null, null)));
    }

    private static CatalogFood food(String name, String category, String brand) {
        return new CatalogFood(
            "id", name, name == null ? null : name.toLowerCase(), brand, null, category,
            null, List.of(), 0, FoodSource.USER, null, FoodStatus.UNVERIFIED, 0, null,
            null, FoodImageStatus.NONE, "creator", null, null);
    }
}
