package com.gte619n.healthfitness.api.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Time-of-day search bias ({@link FoodController#biasByPreferred}): the foods the
 * user habitually logs at the current meal float to the top of the catalog
 * results, with relevance order preserved within each group and nothing added or
 * dropped.
 */
class FoodControllerBiasTest {

    private static CatalogFood food(String id) {
        return new CatalogFood(
            id, id, id.toLowerCase(), null, null, null, null, List.of(), 0,
            FoodSource.USER, null, FoodStatus.UNVERIFIED, 0, null, null,
            FoodImageStatus.NONE, null, null, null, null, null);
    }

    private static List<String> ids(List<CatalogFood> fs) {
        return fs.stream().map(CatalogFood::foodId).toList();
    }

    @Test
    void floatsPreferredFoodsToTopKeepingRelevanceWithinEachGroup() {
        List<CatalogFood> results = List.of(food("a"), food("b"), food("c"), food("d"));

        // "b" and "d" are the user's usual foods at this meal.
        List<CatalogFood> biased = FoodController.biasByPreferred(results, Set.of("b", "d"));

        // Preferred keep their relative order (b before d); the rest keep theirs (a before c).
        assertEquals(List.of("b", "d", "a", "c"), ids(biased));
    }

    @Test
    void noPreferredFoodsIsANoOp() {
        List<CatalogFood> results = List.of(food("a"), food("b"));

        assertSame(results, FoodController.biasByPreferred(results, Set.of()),
            "nothing preferred ⇒ original list returned untouched");
    }

    @Test
    void preferredNotPresentInResultsChangesNothing() {
        List<CatalogFood> results = List.of(food("a"), food("b"));

        List<CatalogFood> biased = FoodController.biasByPreferred(results, Set.of("z"));

        assertEquals(List.of("a", "b"), ids(biased), "a preferred id absent from results is harmless");
    }
}
