package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Creation-time de-dup ({@link FoodCatalogService#findReusable}): a capture whose
 * name differs from an existing food only by case, whitespace or trailing
 * punctuation must reuse that food instead of minting a near-duplicate, while a
 * genuinely different name (or mismatched brand/category) must not.
 */
class FoodCatalogReuseTest {

    private static CatalogFood food(
        String id, String name, String brand, String category,
        FoodStatus status, Instant archivedAt) {
        return new CatalogFood(
            id, name, name.toLowerCase(), brand, null, category, null, List.of(), 0,
            FoodSource.GEMINI_DESCRIPTION, null, status, 0, null, null,
            FoodImageStatus.NONE, null, null, null, null, archivedAt);
    }

    private static CatalogFood food(String id, String name) {
        return food(id, name, null, null, FoodStatus.UNVERIFIED, null);
    }

    /** A repo whose name-prefix path returns the given candidates verbatim. */
    private static FoodCatalogService serviceWith(List<CatalogFood> candidates) {
        FoodCatalogRepository repo = new FoodCatalogRepository() {
            @Override public Optional<CatalogFood> findById(String id) { return Optional.empty(); }
            @Override public List<CatalogFood> searchByNamePrefix(String p, int n) { return candidates; }
            @Override public List<CatalogFood> searchByTokens(List<String> w, int n) { return List.of(); }
            @Override public Optional<CatalogFood> findByBarcode(String c) { return Optional.empty(); }
            @Override public List<CatalogFood> findByImageStatus(FoodImageStatus s, int n) { return List.of(); }
            @Override public void save(CatalogFood f) {}
            @Override public void saveConfirmation(String id, String u) {}
            @Override public int countConfirmations(String id) { return 0; }
        };
        return new FoodCatalogService(repo, 1, null, null);
    }

    @Test
    void reusesDespiteCaseWhitespaceAndTrailingPunctuation() {
        FoodCatalogService svc = serviceWith(List.of(food("cb", "Grilled Chicken Breast")));

        Optional<CatalogFood> hit = svc.findReusable("  grilled   chicken breast. ", null, null);

        assertTrue(hit.isPresent(), "incidental name differences must still reuse");
        assertEquals("cb", hit.get().foodId());
    }

    @Test
    void doesNotReuseAGenuinelyDifferentName() {
        FoodCatalogService svc = serviceWith(List.of(food("cb", "Grilled Chicken Breast")));

        // A narrower/different name is a distinct food, not an incidental variant.
        assertTrue(svc.findReusable("grilled chicken", null, null).isEmpty());
    }

    @Test
    void doesNotReuseArchivedOrDrink() {
        CatalogFood archived = food("a", "Greek Yogurt", null, null, FoodStatus.UNVERIFIED, Instant.now());
        CatalogFood drink = food("d", "Greek Yogurt", null, "drink", FoodStatus.UNVERIFIED, null);
        FoodCatalogService svc = serviceWith(List.of(archived, drink));

        assertTrue(svc.findReusable("greek yogurt", null, null).isEmpty(),
            "a deleted food is never resurrected, and a drink is never a food match");
    }

    @Test
    void respectsBrandAndCategoryGuards() {
        CatalogFood siggis = food("s", "Yogurt", "Siggi's", "product", FoodStatus.UNVERIFIED, null);
        FoodCatalogService svc = serviceWith(List.of(siggis));

        assertFalse(svc.findReusable("yogurt", "Chobani", "product").isPresent(), "brand must match");
        assertFalse(svc.findReusable("yogurt", "Siggi's", "drink").isPresent(), "category must match");
        assertTrue(svc.findReusable("Yogurt ", "siggi's", "product").isPresent(),
            "matching brand+category (case-insensitive) reuses");
    }

    @Test
    void resolveOrCreateReusesInsteadOfMinting() {
        FoodCatalogService svc = serviceWith(List.of(food("cb", "Grilled Chicken Breast")));

        // The messy capture name resolves to the existing food — the create()
        // branch (which would mint a new id) is never taken.
        CatalogFood resolved = svc.resolveOrCreate(
            "user-1", "Grilled chicken  breast", null, null, null,
            null, List.of(), 0, FoodSource.GEMINI_DESCRIPTION, null);

        assertEquals("cb", resolved.foodId(), "reuse wins over mint");
    }
}
