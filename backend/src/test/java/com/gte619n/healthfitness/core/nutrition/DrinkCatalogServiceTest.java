package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Drink catalog behaviour (IMPL-DRINK-01): createDrink stores alcohol facts and
 * calories-including-alcohol, the per-100 normalization reproduces the serving at
 * quantity 1 and scales linearly (D22), validation rejects bad drinks, and
 * archive hides a drink from the listing.
 */
class DrinkCatalogServiceTest {

    /** In-memory fake repo capturing saves so we can assert stored state. */
    private static final class FakeRepo implements FoodCatalogRepository {
        final List<CatalogFood> store = new ArrayList<>();

        @Override public Optional<CatalogFood> findById(String id) {
            return store.stream().filter(f -> f.foodId().equals(id))
                .reduce((a, b) -> b); // last write wins
        }
        @Override public List<CatalogFood> searchByNamePrefix(String p, int n) { return List.of(); }
        @Override public List<CatalogFood> searchByTokens(List<String> w, int n) { return List.of(); }
        @Override public Optional<CatalogFood> findByBarcode(String c) { return Optional.empty(); }
        @Override public List<CatalogFood> findByImageStatus(FoodImageStatus s, int n) { return List.of(); }
        @Override public void save(CatalogFood f) { store.add(f); }
        @Override public void saveConfirmation(String id, String u) {}
        @Override public int countConfirmations(String id) { return 0; }
        @Override public List<CatalogFood> findByCreatedByAndCategory(String userId, String category) {
            List<CatalogFood> out = new ArrayList<>();
            for (CatalogFood f : store) {
                if (userId.equals(f.createdBy()) && category.equalsIgnoreCase(f.category())) {
                    out.removeIf(e -> e.foodId().equals(f.foodId())); // keep last
                    out.add(f);
                }
            }
            return out;
        }
    }

    private static FoodCatalogService serviceWith(FakeRepo repo) {
        return new FoodCatalogService(repo, 1, emptyProvider(), emptyProvider());
    }

    /** An ObjectProvider that resolves to nothing (no barcode lookup / image service). */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public T getObject() { throw new UnsupportedOperationException(); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    @Test
    void createDrinkStoresAlcoholFactsAndCategory() {
        FakeRepo repo = new FakeRepo();
        // gin & tonic: 12% ABV, 250 ml, 15 g sugar mixer
        CatalogFood drink = serviceWith(repo).createDrink(
            "u1", "Gin & Tonic", 12.0, 250.0,
            new Macros(null, 0.0, 15.0, 0.0, 0.0, 15.0), null, null);

        assertTrue(drink.isDrink());
        assertNotNull(drink.alcohol());
        assertEquals(12.0, drink.alcohol().abvPercent());
        assertEquals(250.0, drink.alcohol().servingVolumeMl());
        // 250 × 0.12 × 0.789 ≈ 23.7 g
        assertEquals(23.7, drink.alcohol().alcoholGrams(), 0.3);
        assertTrue(drink.alcohol().standardDrinks() > 1.5);
        assertEquals(1, drink.servingSizes().size());
        assertEquals(250.0, drink.servingSizes().get(0).grams());
    }

    @Test
    void servingCaloriesIncludeAlcoholAndReproduceAtQuantityOne() {
        FakeRepo repo = new FakeRepo();
        CatalogFood drink = serviceWith(repo).createDrink(
            "u1", "Gin & Tonic", 12.0, 250.0,
            new Macros(null, 0.0, 15.0, 0.0, 0.0, 15.0), null, null);

        double grams = drink.alcohol().alcoholGrams();
        double expectedServingKcal = 15.0 * 4.0 + grams * 7.0; // carbs + alcohol
        // Reconstruct serving from stored per-100 macros at quantity 1.
        double volume = drink.servingSizes().get(0).grams();
        Macros serving = drink.macrosPer100g().scale(volume / 100.0);
        assertEquals(expectedServingKcal, serving.caloriesKcal(), 0.5);
    }

    @Test
    void multiplierScalesLinearly() {
        FakeRepo repo = new FakeRepo();
        CatalogFood drink = serviceWith(repo).createDrink(
            "u1", "Negroni", 26.0, 90.0, Macros.zero(), null, null);
        double volume = drink.servingSizes().get(0).grams();
        Macros single = drink.macrosPer100g().scale(volume * 1.0 / 100.0);
        Macros doubleShot = drink.macrosPer100g().scale(volume * 2.0 / 100.0);
        assertEquals(2 * single.caloriesKcal(), doubleShot.caloriesKcal(), 0.01);
    }

    @Test
    void createRejectsMissingAlcoholFacts() {
        FoodCatalogService svc = serviceWith(new FakeRepo());
        assertThrows(IllegalArgumentException.class, () ->
            svc.createDrink("u1", "Mystery", null, 100.0, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            svc.createDrink("u1", "Mystery", 12.0, 0.0, null, null, null));
    }

    @Test
    void listMyDrinksExcludesArchivedAndOtherUsers() {
        FakeRepo repo = new FakeRepo();
        FoodCatalogService svc = serviceWith(repo);
        CatalogFood mine = svc.createDrink("u1", "Negroni", 26.0, 90.0, Macros.zero(), null, "d1");
        svc.createDrink("u2", "Martini", 30.0, 90.0, Macros.zero(), null, "d2");
        // Make createdAt deterministic for the archive lookup path.
        repo.store.replaceAll(f -> f.createdAt() == null ? withCreatedAt(f) : f);

        assertEquals(1, svc.listMyDrinks("u1").size(), "only my drinks");

        svc.archiveDrink(mine.foodId());
        assertTrue(svc.listMyDrinks("u1").isEmpty(), "archived drink hidden");
        assertFalse(svc.find(mine.foodId()).orElseThrow().isArchived() == false,
            "archived doc still exists");
    }

    private static CatalogFood withCreatedAt(CatalogFood f) {
        return new CatalogFood(
            f.foodId(), f.name(), f.nameLower(), f.brand(), f.barcode(), f.category(),
            f.macrosPer100g(), f.servingSizes(), f.defaultServingIndex(), f.source(),
            f.sourceRef(), f.status(), f.confirmationCount(), f.verifiedAt(), f.imageUrl(),
            f.imageStatus(), f.createdBy(), Instant.now(), f.updatedAt(), f.alcohol(), f.archivedAt());
    }
}
