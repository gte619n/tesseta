package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.nutrition.MealPhotoAnalyzer.MealAnalysis;
import com.gte619n.healthfitness.core.nutrition.MealPhotoAnalyzer.MealItem;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit-tests the deterministic finalize logic of {@link MealCaptureService}
 * with in-memory fakes (no Gemini, no GCS). Drives {@code analyzeAndFinalize}
 * directly (skipping the async hop) to assert how a placeholder is filled in for
 * a packaged product, a multi-ingredient meal, and an unrecognized photo.
 */
class MealCaptureServiceTest {

    private static final String USER = "u-cap";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 2);
    private static final byte[] BYTES = "img".getBytes();

    @Test
    void packagedProduct_finalizesAsSingleFood_named_andReady() {
        Fixture f = new Fixture();
        FoodEntry placeholder = f.nutrition.beginAnalyzingEntry(USER, DATE, MealType.SNACK, "ref://1");
        assertTrue(placeholder.isAnalyzing());

        MealPhotoAnalyzer analyzer = analyzer(new MealAnalysis(
            "Greek yogurt", true, List.of(
                new MealItem("Greek yogurt", 170.0,
                    new Macros(59.0, 10.0, 3.6, 0.4, 0.0, 3.2), 0.9))));

        f.svc.analyzeAndFinalize(USER, DATE, placeholder.entryId(), BYTES, "image/jpeg", "ref://1", analyzer);

        FoodEntry done = f.entries.findById(USER, DATE, placeholder.entryId()).orElseThrow();
        assertEquals(EntryAnalysisStatus.READY, done.analysisStatus());
        assertEquals("Greek yogurt", done.foodName());
        assertFalse(done.isComposite(), "a packaged product is a single food, not composite");
        assertNotNull(done.foodId(), "a catalog food backs the product image");
        // 170 g at derived 58 kcal/100 g (10·4 + 3.6·4 + 0.4·9) = 98.6 kcal
        assertEquals((10.0 * 4 + 3.6 * 4 + 0.4 * 9) * 1.7, done.macros().caloriesKcal(), 1e-6);
    }

    @Test
    void preparedMeal_finalizesAsComposite_withNaturalName_andIngredients() {
        Fixture f = new Fixture();
        FoodEntry placeholder = f.nutrition.beginAnalyzingEntry(USER, DATE, MealType.DINNER, "ref://2");

        MealPhotoAnalyzer analyzer = analyzer(new MealAnalysis(
            "Salmon and broccoli", false, List.of(
                new MealItem("Grilled salmon", 200.0,
                    new Macros(208.0, 20.0, 0.0, 13.0, 0.0, 0.0), 0.9),
                new MealItem("Steamed broccoli", 100.0,
                    new Macros(35.0, 2.4, 7.0, 0.4, 3.3, 1.5), 0.8))));

        f.svc.analyzeAndFinalize(USER, DATE, placeholder.entryId(), BYTES, "image/jpeg", "ref://2", analyzer);

        FoodEntry done = f.entries.findById(USER, DATE, placeholder.entryId()).orElseThrow();
        assertEquals(EntryAnalysisStatus.READY, done.analysisStatus());
        assertEquals("Salmon and broccoli", done.foodName(),
            "the dish name is the model's natural name, not a joined ingredient list");
        assertTrue(done.isComposite());
        assertEquals(2, done.ingredients().size());
        assertEquals("Grilled salmon", done.ingredients().get(0).name());
        // total = 200 g salmon (derived 197 kcal/100 g) + 100 g broccoli (derived 41.2)
        assertEquals((20.0 * 4 + 13.0 * 9) * 2.0 + (2.4 * 4 + 7.0 * 4 + 0.4 * 9),
            done.macros().caloriesKcal(), 1e-6);
    }

    @Test
    void noFoodRecognized_marksFailed() {
        Fixture f = new Fixture();
        FoodEntry placeholder = f.nutrition.beginAnalyzingEntry(USER, DATE, MealType.LUNCH, "ref://3");

        MealPhotoAnalyzer analyzer = analyzer(new MealAnalysis(null, false, List.of()));

        f.svc.analyzeAndFinalize(USER, DATE, placeholder.entryId(), BYTES, "image/jpeg", "ref://3", analyzer);

        FoodEntry done = f.entries.findById(USER, DATE, placeholder.entryId()).orElseThrow();
        assertEquals(EntryAnalysisStatus.FAILED, done.analysisStatus());
        assertNull(done.foodId());
    }

    @Test
    void sameImageUploadedAgain_isDedupedSilently() {
        Fixture f = new Fixture(analyzer(new MealAnalysis(
            "Greek yogurt", true, List.of(
                new MealItem("Greek yogurt", 170.0,
                    new Macros(59.0, 10.0, 3.6, 0.4, 0.0, 3.2), 0.9)))));

        FoodEntry first = f.svc.captureMeal(USER, DATE, MealType.SNACK, BYTES, "image/jpeg");
        FoodEntry again = f.svc.captureMeal(USER, DATE, MealType.SNACK, BYTES, "image/jpeg");

        assertEquals(first.entryId(), again.entryId(),
            "re-uploading the same image returns the existing entry, not a new one");
        assertEquals(1, f.entries.findByDate(USER, DATE).size(),
            "the duplicate upload creates no second entry");
    }

    @Test
    void distinctImages_eachLogTheirOwnEntry() {
        Fixture f = new Fixture(analyzer(new MealAnalysis(
            "Greek yogurt", true, List.of(
                new MealItem("Greek yogurt", 170.0,
                    new Macros(59.0, 10.0, 3.6, 0.4, 0.0, 3.2), 0.9)))));

        f.svc.captureMeal(USER, DATE, MealType.SNACK, "img-a".getBytes(), "image/jpeg");
        f.svc.captureMeal(USER, DATE, MealType.SNACK, "img-b".getBytes(), "image/jpeg");

        assertEquals(2, f.entries.findByDate(USER, DATE).size(),
            "separate uploads of different images each log their own entry");
    }

    @Test
    void failedPhoto_isNotDeduped_soUserCanRetry() {
        Fixture f = new Fixture();
        FoodEntry e = f.nutrition.beginAnalyzingEntry(USER, DATE, MealType.LUNCH, "ref://x", "hash-x");
        assertTrue(f.nutrition.findActivePhotoByHash(USER, DATE, "hash-x").isPresent());

        f.nutrition.markAnalysisFailed(USER, DATE, e.entryId());
        assertTrue(f.nutrition.findActivePhotoByHash(USER, DATE, "hash-x").isEmpty(),
            "a failed capture doesn't block re-uploading the same image to retry");
    }

    // ---- fixture + fakes ----

    private static final class Fixture {
        final InMemEntries entries = new InMemEntries();
        final NutritionService nutrition =
            new NutritionService(new InMemNutrition(), entries, noopPublisher());
        final FoodCatalogService catalog =
            new FoodCatalogService(new FakeCatalogRepo(), 1, empty(), empty());
        final FoodEntryImageService images =
            new FoodEntryImageService(entries, empty(), empty(), empty());
        final MealCaptureService svc;

        Fixture() { this(null); }

        // With an analyzer (and a null photo-store, so photoRef is null) the
        // full captureMeal path — hash, dedupe, placeholder, async finalize —
        // can be driven end to end.
        Fixture(MealPhotoAnalyzer analyzer) {
            this.svc = new MealCaptureService(
                analyzer != null ? provider(analyzer) : empty(), empty(),
                catalog, nutrition, images,
                new com.gte619n.healthfitness.core.push.SyncChangeNotifier(event -> { }));
        }
    }

    private static MealPhotoAnalyzer analyzer(MealAnalysis analysis) {
        return new MealPhotoAnalyzer() {
            @Override public List<MealItem> analyze(byte[] bytes, String mime) { return analysis.items(); }
            @Override public MealAnalysis analyzeMeal(byte[] bytes, String mime) { return analysis; }
        };
    }

    private static MetricChangedPublisher noopPublisher() {
        return new MetricChangedPublisher(event -> { });
    }

    private static <T> ObjectProvider<T> empty() {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { throw new IllegalStateException("no bean"); }
            @Override public T getObject() { throw new IllegalStateException("no bean"); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static final class InMemNutrition implements NutritionDailyLogRepository {
        private final Map<String, NutritionDailyLog> rows = new ConcurrentHashMap<>();
        @Override public Optional<NutritionDailyLog> findByDate(String userId, LocalDate date) {
            return Optional.ofNullable(rows.get(date.toString()));
        }
        @Override public List<NutritionDailyLog> findByDateRange(String userId, LocalDate from, LocalDate to) {
            return List.copyOf(rows.values());
        }
        @Override public void save(NutritionDailyLog log) { rows.put(log.date().toString(), log); }
    }

    private static final class InMemEntries implements FoodEntryRepository {
        private final Map<String, FoodEntry> rows = new ConcurrentHashMap<>();
        private static String key(LocalDate date, String entryId) { return date + "/" + entryId; }
        @Override public List<FoodEntry> findByDate(String userId, LocalDate date) {
            return rows.values().stream().filter(e -> e.date().equals(date)).toList();
        }
        @Override public Optional<FoodEntry> findById(String userId, LocalDate date, String entryId) {
            return Optional.ofNullable(rows.get(key(date, entryId)));
        }
        @Override public Optional<FoodEntry> findByContentHash(String userId, LocalDate date, String contentHash) {
            return rows.values().stream()
                .filter(e -> e.date().equals(date) && contentHash != null && contentHash.equals(e.contentHash()))
                .findFirst();
        }
        @Override public void save(FoodEntry entry) { rows.put(key(entry.date(), entry.entryId()), entry); }
        @Override public void delete(String userId, LocalDate date, String entryId) { rows.remove(key(date, entryId)); }
    }

    private static final class FakeCatalogRepo implements FoodCatalogRepository {
        private final java.util.List<CatalogFood> foods = new java.util.ArrayList<>();
        @Override public Optional<CatalogFood> findById(String foodId) {
            return foods.stream().filter(f -> f.foodId().equals(foodId)).findFirst();
        }
        @Override public List<CatalogFood> searchByNamePrefix(String prefixLower, int limit) {
            return List.of();
        }
        @Override public Optional<CatalogFood> findByBarcode(String code) { return Optional.empty(); }
        @Override public List<CatalogFood> findByImageStatus(FoodImageStatus status, int limit) {
            return List.of();
        }
        @Override public void save(CatalogFood food) { foods.add(food); }
        @Override public void saveConfirmation(String foodId, String userId) { }
        @Override public int countConfirmations(String foodId) { return 0; }
    }
}
