package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Search behaviour of {@link FoodCatalogService} over a fake repository: token
 * (any-word) results and the legacy name-prefix results are unioned, deduped by
 * id, and ranked exact &gt; name-prefix &gt; name-contains &gt; token-only with
 * alphabetical tie-breaks. No Firestore involved.
 */
class FoodCatalogSearchTest {

    private static CatalogFood food(String id, String name) {
        return new CatalogFood(
            id, name, name.toLowerCase(), null, null, null, null, List.of(), 0,
            FoodSource.USER, null, FoodStatus.UNVERIFIED, 0, null, null,
            FoodImageStatus.NONE, null, null, null);
    }

    /** A repo whose two search paths return fixed, controllable lists. */
    private static FoodCatalogService serviceWith(
        List<CatalogFood> tokenHits, List<CatalogFood> prefixHits) {
        FoodCatalogRepository repo = new FoodCatalogRepository() {
            @Override public Optional<CatalogFood> findById(String id) { return Optional.empty(); }
            @Override public List<CatalogFood> searchByNamePrefix(String p, int n) { return prefixHits; }
            @Override public List<CatalogFood> searchByTokens(List<String> w, int n) { return tokenHits; }
            @Override public Optional<CatalogFood> findByBarcode(String c) { return Optional.empty(); }
            @Override public List<CatalogFood> findByImageStatus(FoodImageStatus s, int n) { return List.of(); }
            @Override public void save(CatalogFood f) {}
            @Override public void saveConfirmation(String id, String u) {}
            @Override public int countConfirmations(String id) { return 0; }
        };
        return new FoodCatalogService(repo, 1, null, null);
    }

    @Test
    void blankQueryReturnsNothing() {
        FoodCatalogService svc = serviceWith(List.of(food("a", "Apple")), List.of());
        assertTrue(svc.search("   ").isEmpty());
        assertTrue(svc.search(null).isEmpty());
    }

    @Test
    void unionsTokenAndPrefixResultsAndDedupesById() {
        CatalogFood chickenBreast = food("cb", "Chicken Breast");
        FoodCatalogService svc = serviceWith(
            List.of(food("gc", "Grilled Chicken"), chickenBreast),
            // chickenBreast appears in both paths; a legacy-only food only here.
            List.of(chickenBreast, food("cs", "Chicken Soup")));

        List<String> ids = svc.search("chicken").stream().map(CatalogFood::foodId).toList();

        assertEquals(3, ids.size(), "deduped union of both paths");
        assertEquals(1, ids.stream().filter("cb"::equals).count(), "no duplicate");
    }

    @Test
    void ranksPrefixAboveContainsAndAlphabetisesTies() {
        FoodCatalogService svc = serviceWith(
            List.of(food("gc", "Grilled Chicken"), food("cb", "Chicken Breast")),
            List.of(food("cs", "Chicken Soup")));

        List<String> names = svc.search("chicken").stream().map(CatalogFood::name).toList();

        // Both "Chicken ..." start with the query (rank 2, alpha order); the
        // contains-only "Grilled Chicken" (rank 1) sinks below them.
        assertEquals(List.of("Chicken Breast", "Chicken Soup", "Grilled Chicken"), names);
    }

    @Test
    void exactNameMatchRanksFirst() {
        FoodCatalogService svc = serviceWith(
            List.of(food("cbd", "Chicken Breast Deluxe"), food("cb", "Chicken Breast")),
            List.of());

        List<String> names = svc.search("chicken breast").stream().map(CatalogFood::name).toList();

        assertEquals("Chicken Breast", names.get(0), "exact match floats to the top");
    }
}
