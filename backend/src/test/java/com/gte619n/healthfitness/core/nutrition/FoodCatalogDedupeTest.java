package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link FoodCatalogService#archive} soft-deletes a food and
 * {@link FoodCatalogService#dedupe} collapses capture-minted duplicates, keeping
 * the best member of each name/brand/macro group. No Firestore involved — a tiny
 * in-memory repo records saves so we can assert what got archived.
 */
class FoodCatalogDedupeTest {

    /** Mutable in-memory catalog: save() overwrites; findAll() returns everything. */
    private static final class FakeRepo implements FoodCatalogRepository {
        final Map<String, CatalogFood> byId = new LinkedHashMap<>();

        @Override public Optional<CatalogFood> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public List<CatalogFood> searchByNamePrefix(String p, int n) {
            return byId.values().stream()
                .filter(f -> f.nameLower() != null && f.nameLower().startsWith(p))
                .limit(n)
                .toList();
        }
        @Override public List<CatalogFood> searchByTokens(List<String> w, int n) { return List.of(); }
        @Override public Optional<CatalogFood> findByBarcode(String c) { return Optional.empty(); }
        @Override public List<CatalogFood> findByImageStatus(FoodImageStatus s, int n) { return List.of(); }
        @Override public List<CatalogFood> findAll() { return new ArrayList<>(byId.values()); }
        @Override public void save(CatalogFood f) { byId.put(f.foodId(), f); }
        @Override public void saveConfirmation(String id, String u) {}
        @Override public int countConfirmations(String id) { return 0; }
    }

    private static FoodCatalogService serviceWith(FakeRepo repo) {
        return new FoodCatalogService(repo, 1, empty(), empty());
    }

    /** An ObjectProvider with no bean — create()'s image pipeline is a no-op. */
    private static <T> ObjectProvider<T> empty() {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { throw new IllegalStateException("no bean"); }
            @Override public T getObject() { throw new IllegalStateException("no bean"); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    /** A catalog food with the fields dedupe/keep-scoring care about. */
    private static CatalogFood food(
        String id, String name, double kcal, FoodSource source,
        FoodStatus status, FoodImageStatus image, int confirmations, Instant createdAt) {
        return new CatalogFood(
            id, name, name.toLowerCase(), null, null, null,
            new Macros(kcal, 20.0, 0.0, 5.0, null, null), List.of(), 0,
            source, null, status, confirmations, null, null, image, null,
            createdAt, null, null, null);
    }

    private static CatalogFood gemini(String id, String name, Instant createdAt) {
        return food(id, name, 165.0, FoodSource.GEMINI_PHOTO,
            FoodStatus.UNVERIFIED, FoodImageStatus.NONE, 0, createdAt);
    }

    @Test
    void archiveSetsArchivedAtAndKeepsTheDocument() {
        FakeRepo repo = new FakeRepo();
        repo.save(gemini("cb", "Chicken Breast", Instant.now()));

        CatalogFood archived = serviceWith(repo).archive("cb");

        assertTrue(archived.isArchived(), "archive() stamps archivedAt");
        assertTrue(repo.byId.containsKey("cb"), "the document is kept, not deleted");
        assertTrue(repo.byId.get("cb").isArchived());
    }

    @Test
    void dedupeArchivesDuplicatesKeepingTheOldest() {
        FakeRepo repo = new FakeRepo();
        Instant older = Instant.now().minusSeconds(1000);
        Instant newer = Instant.now();
        repo.save(gemini("old", "Chicken Breast", older));
        repo.save(gemini("new", "Chicken Breast", newer));

        int archived = serviceWith(repo).dedupe();

        assertEquals(1, archived, "one of the identical pair is archived");
        assertFalse(repo.byId.get("old").isArchived(), "the original (oldest) is kept");
        assertTrue(repo.byId.get("new").isArchived(), "the newer duplicate is archived");
    }

    @Test
    void dedupePrefersVerifiedWithImageOverNewerRawEntry() {
        FakeRepo repo = new FakeRepo();
        // A curated, verified, imaged entry vs. a newer raw capture of the same food.
        repo.save(food("good", "Chicken Breast", 165.0, FoodSource.USDA,
            FoodStatus.VERIFIED, FoodImageStatus.READY, 3, Instant.now().minusSeconds(500)));
        repo.save(gemini("raw", "Chicken Breast", Instant.now()));

        serviceWith(repo).dedupe();

        assertFalse(repo.byId.get("good").isArchived(), "verified+imaged entry is kept");
        assertTrue(repo.byId.get("raw").isArchived(), "raw duplicate is archived");
    }

    @Test
    void dedupeDoesNotMergeDifferentMacros() {
        FakeRepo repo = new FakeRepo();
        repo.save(gemini("a", "Chicken Breast", Instant.now()));
        // Same name, materially different macros ⇒ a genuinely different food.
        repo.save(food("b", "Chicken Breast", 250.0, FoodSource.GEMINI_PHOTO,
            FoodStatus.UNVERIFIED, FoodImageStatus.NONE, 0, Instant.now()));

        int archived = serviceWith(repo).dedupe();

        assertEquals(0, archived, "different macros are not treated as duplicates");
    }

    // ---- creation-time guard (resolveOrCreate) ----

    @Test
    void resolveOrCreateReusesExistingSameNameFood() {
        FakeRepo repo = new FakeRepo();
        repo.save(gemini("existing", "Grilled Chicken", Instant.now().minusSeconds(100)));

        // A fresh capture of the same ingredient — even with different estimated
        // macros — reuses the existing catalog food instead of minting a new one.
        CatalogFood got = serviceWith(repo).resolveOrCreate(
            "u", "Grilled Chicken", null, null, null,
            new Macros(999.0, 1.0, 1.0, 1.0, null, null), List.of(), 0,
            FoodSource.GEMINI_PHOTO, null);

        assertEquals("existing", got.foodId(), "same-name capture reuses the existing food");
        assertEquals(1, repo.byId.size(), "no duplicate is minted");
    }

    @Test
    void resolveOrCreateMintsForANewName() {
        FakeRepo repo = new FakeRepo();
        repo.save(gemini("existing", "Grilled Chicken", Instant.now()));

        CatalogFood got = serviceWith(repo).resolveOrCreate(
            "u", "Steamed Broccoli", null, null, null,
            new Macros(35.0, 2.0, 7.0, 0.0, null, null), List.of(), 0,
            FoodSource.GEMINI_PHOTO, null);

        assertNotEquals("existing", got.foodId());
        assertEquals("Steamed Broccoli", got.name());
        assertEquals(2, repo.byId.size(), "a genuinely new ingredient is minted");
    }

    @Test
    void resolveOrCreateNeverResurrectsADeletedFood() {
        FakeRepo repo = new FakeRepo();
        repo.save(gemini("old", "Grilled Chicken", Instant.now()));
        FoodCatalogService svc = serviceWith(repo);
        svc.archive("old"); // the user deleted this duplicate

        CatalogFood got = svc.resolveOrCreate(
            "u", "Grilled Chicken", null, null, null,
            new Macros(165.0, 20.0, 0.0, 5.0, null, null), List.of(), 0,
            FoodSource.GEMINI_PHOTO, null);

        assertNotEquals("old", got.foodId(), "an archived (deleted) food is never reused");
        assertFalse(got.isArchived(), "the freshly minted replacement is active");
    }

    @Test
    void dedupeIgnoresAlreadyArchivedFoods() {
        FakeRepo repo = new FakeRepo();
        repo.save(gemini("live", "Chicken Breast", Instant.now()));
        serviceWith(repo).archive("live");

        int archived = serviceWith(repo).dedupe();

        assertEquals(0, archived, "a lone (already-archived) food yields nothing to dedupe");
    }
}
