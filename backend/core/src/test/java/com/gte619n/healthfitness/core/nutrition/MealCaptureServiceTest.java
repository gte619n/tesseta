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
        // 170 g of 59 kcal/100 g ≈ 100.3 kcal
        assertEquals(100.3, done.macros().caloriesKcal(), 1e-6);
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
        // total = 200 g salmon (416 kcal) + 100 g broccoli (35 kcal)
        assertEquals(451.0, done.macros().caloriesKcal(), 1e-6);
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

    // ---- fixture + fakes ----

    private static final class Fixture {
        final InMemEntries entries = new InMemEntries();
        final NutritionService nutrition =
            new NutritionService(new InMemNutrition(), entries, noopPublisher());
        final FoodCatalogService catalog =
            new FoodCatalogService(new FakeCatalogRepo(), 1, empty(), empty());
        final FoodEntryImageService images =
            new FoodEntryImageService(entries, empty(), empty(), empty());
        final MealCaptureService svc =
            new MealCaptureService(empty(), empty(), catalog, nutrition, images);
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
