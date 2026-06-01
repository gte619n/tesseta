package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit-tests {@link NutritionCaptureService} orchestration with fake ports
 * (no Gemini, no GCS). Confirms: photo is stored and its ref propagated;
 * meal items are mapped and matched to catalog foods by name; label drafts are
 * tagged {@code GEMINI_LABEL}/{@code UNVERIFIED} with the barcode attached; and
 * that the service is constructible/operable when the analyzer/storage ports are
 * absent (the {@code ObjectProvider} seam used in core-only contexts).
 */
class NutritionCaptureServiceTest {

    private static final String USER = "u-cap";
    private static final byte[] BYTES = "img".getBytes();

    @Test
    void analyzeMeal_storesPhoto_mapsItems_andMatchesCatalog() {
        FakeCatalogRepo repo = new FakeCatalogRepo();
        CatalogFood chicken = food("f-chicken", "Chicken breast, grilled");
        repo.add(chicken);
        FoodCatalogService catalog = new FoodCatalogService(repo, 1, empty(), empty());

        MealPhotoAnalyzer analyzer = (bytes, mime) -> List.of(
            new MealPhotoAnalyzer.MealItem(
                "Chicken breast", 150.0,
                new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0), 0.9),
            new MealPhotoAnalyzer.MealItem(
                "Mystery sauce", 30.0,
                new Macros(200.0, 1.0, 5.0, 18.0, 0.0, 2.0), 0.4)
        );
        FakeStore store = new FakeStore("ref://meal/1");

        NutritionCaptureService svc = new NutritionCaptureService(
            provider(analyzer), empty(), provider(store), catalog);

        MealProposal proposal = svc.analyzeMeal(USER, BYTES, "image/jpeg");

        assertEquals("ref://meal/1", proposal.photoRef());
        assertEquals(USER, store.lastUserId);
        assertEquals(2, proposal.items().size());

        MealProposal.MealProposalItem first = proposal.items().get(0);
        assertEquals("Chicken breast", first.name());
        assertEquals(150.0, first.estimatedPortionGrams(), 1e-9);
        assertEquals("f-chicken", first.matchedFoodId(), "name search should match the catalog food");

        assertNull(proposal.items().get(1).matchedFoodId(), "no catalog match -> null id");
    }

    @Test
    void analyzeLabel_buildsGeminiLabelDraft_normalizedAndBarcoded() {
        FoodCatalogService catalog = new FoodCatalogService(new FakeCatalogRepo(), 1, empty(), empty());
        // Analyzer is responsible for per-100g normalization; here it returns
        // already-normalized macros as the contract specifies.
        NutritionLabelAnalyzer analyzer = (bytes, mime) -> new NutritionLabelAnalyzer.LabelExtraction(
            "Protein Bar", "ACME", 60.0, 12.0,
            new Macros(400.0, 33.0, 40.0, 13.0, 8.0, 5.0));
        FakeStore store = new FakeStore("ref://label/9");

        NutritionCaptureService svc = new NutritionCaptureService(
            empty(), provider(analyzer), provider(store), catalog);

        LabelProposal proposal = svc.analyzeLabel(USER, BYTES, "image/png", "0123456789012");

        assertEquals("ref://label/9", proposal.photoRef());
        LabelProposal.FoodDraft draft = proposal.food();
        assertEquals("Protein Bar", draft.name());
        assertEquals("ACME", draft.brand());
        assertEquals("0123456789012", draft.barcode());
        assertEquals(FoodSource.GEMINI_LABEL, draft.source());
        assertEquals(FoodStatus.UNVERIFIED, draft.status());
        assertEquals(400.0, draft.macrosPer100g().caloriesKcal(), 1e-9);
        // serving size present -> first serving is the label serving, then 100 g
        assertEquals("1 serving", draft.servingSizes().get(0).label());
        assertEquals(60.0, draft.servingSizes().get(0).grams(), 1e-9);
        assertEquals(0, draft.defaultServingIndex());
    }

    @Test
    void analyzeLabel_withoutBarcode_leavesBarcodeNull() {
        FoodCatalogService catalog = new FoodCatalogService(new FakeCatalogRepo(), 1, empty(), empty());
        NutritionLabelAnalyzer analyzer = (bytes, mime) -> new NutritionLabelAnalyzer.LabelExtraction(
            "Soup", null, null, null, new Macros(50.0, 2.0, 7.0, 1.0, 1.0, 2.0));
        NutritionCaptureService svc = new NutritionCaptureService(
            empty(), provider(analyzer), empty(), catalog);

        LabelProposal proposal = svc.analyzeLabel(USER, BYTES, "image/jpeg", "  ");
        assertNull(proposal.food().barcode());
        assertNull(proposal.photoRef(), "no storage port -> null photoRef");
        // no serving size -> only the 100 g fallback serving
        assertEquals(1, proposal.food().servingSizes().size());
        assertEquals("100 g", proposal.food().servingSizes().get(0).label());
    }

    @Test
    void analyzeMeal_withoutAnalyzer_throwsIllegalState() {
        FoodCatalogService catalog = new FoodCatalogService(new FakeCatalogRepo(), 1, empty(), empty());
        NutritionCaptureService svc = new NutritionCaptureService(
            empty(), empty(), empty(), catalog);
        assertThrows(IllegalStateException.class,
            () -> svc.analyzeMeal(USER, BYTES, "image/jpeg"));
    }

    @Test
    void analyzeMeal_withEmptyPhoto_throwsIllegalArgument() {
        FoodCatalogService catalog = new FoodCatalogService(new FakeCatalogRepo(), 1, empty(), empty());
        NutritionCaptureService svc = new NutritionCaptureService(
            provider((b, m) -> List.of()), empty(), empty(), catalog);
        assertThrows(IllegalArgumentException.class,
            () -> svc.analyzeMeal(USER, new byte[0], "image/jpeg"));
    }

    // ---- macros sanity used by the api per-portion math ----

    @Test
    void macrosScale_isExactlyPerPortionMath() {
        Macros per100g = new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0);
        // 150 g portion => factor 1.5
        Macros forPortion = per100g.scale(150.0 / 100.0);
        assertEquals(247.5, forPortion.caloriesKcal(), 1e-9);
        assertEquals(46.5, forPortion.proteinGrams(), 1e-9);
        assertEquals(5.4, forPortion.fatGrams(), 1e-9);
    }

    // ---- fakes ----

    private static CatalogFood food(String id, String name) {
        return new CatalogFood(
            id, name, name.toLowerCase(), null, null, null,
            new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0),
            List.of(new ServingSize("100 g", 100.0)), 0,
            FoodSource.USER, null, FoodStatus.UNVERIFIED, 0, null,
            null, FoodImageStatus.NONE, "creator", Instant.now(), Instant.now());
    }

    private static <T> ObjectProvider<T> empty() {
        return provider(null);
    }

    /** Minimal ObjectProvider returning a single (or no) bean for getIfAvailable. */
    private static <T> ObjectProvider<T> provider(T bean) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return require(); }
            @Override public T getObject() { return require(); }
            @Override public T getIfAvailable() { return bean; }
            @Override public T getIfUnique() { return bean; }
            private T require() {
                if (bean == null) throw new IllegalStateException("no bean");
                return bean;
            }
        };
    }

    private static final class FakeStore implements MealPhotoStore {
        private final String ref;
        String lastUserId;
        FakeStore(String ref) { this.ref = ref; }
        @Override public String store(String userId, byte[] imageBytes, String mimeType) {
            this.lastUserId = userId;
            return ref;
        }
    }

    private static final class FakeCatalogRepo implements FoodCatalogRepository {
        private final java.util.List<CatalogFood> foods = new java.util.ArrayList<>();
        void add(CatalogFood f) { foods.add(f); }
        @Override public Optional<CatalogFood> findById(String foodId) {
            return foods.stream().filter(f -> f.foodId().equals(foodId)).findFirst();
        }
        @Override public List<CatalogFood> searchByNamePrefix(String prefixLower, int limit) {
            return foods.stream()
                .filter(f -> f.nameLower().startsWith(prefixLower))
                .limit(limit)
                .toList();
        }
        @Override public Optional<CatalogFood> findByBarcode(String code) { return Optional.empty(); }
        @Override public List<CatalogFood> findByImageStatus(FoodImageStatus status, int limit) {
            return foods.stream().filter(f -> f.imageStatus() == status).limit(limit).toList();
        }
        @Override public void save(CatalogFood food) { foods.add(food); }
        @Override public void saveConfirmation(String foodId, String userId) {}
        @Override public int countConfirmations(String foodId) { return 0; }
    }
}
