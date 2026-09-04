package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.nutrition.MealAdjustmentService.AcceptedAdjustment;
import com.gte619n.healthfitness.core.nutrition.MealAdjustmentService.AcceptedItem;
import com.gte619n.healthfitness.core.nutrition.MealAdjustmentService.AdjustmentProposal;
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
 * Unit-tests the deterministic preview/apply logic of
 * {@link MealAdjustmentService} with in-memory fakes (no Gemini, no GCS). A fake
 * {@link MealAdjustmentAnalyzer} stands in for the model so the tests assert how
 * a correction is turned into a proposal and then persisted onto the entry.
 */
class MealAdjustmentServiceTest {

    private static final String USER = "u-adj";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 4);

    @Test
    void preview_buildsProposalFromModel_withBeforeAndAfterTotals() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();

        AdjustmentProposal p = f.svc.preview(
            USER, DATE, entry.entryId(), "that's pearl couscous, not lentils");

        assertEquals("Pearl couscous and rice", p.mealName());
        assertEquals(2, p.items().size());
        assertEquals("Pearl couscous", p.items().get(0).name());
        assertEquals("White rice", p.items().get(1).name());
        // oldTotals are the entry's frozen macros; newTotals reflect the revision.
        assertEquals(entry.macros().caloriesKcal(), p.oldTotals().caloriesKcal(), 1e-6);
        double expectedNew =
            (3.8 * 4 + 23.0 * 4 + 0.2 * 9) * 1.5   // 150 g couscous
            + (2.7 * 4 + 28.0 * 4 + 0.3 * 9) * 1.0; // 100 g rice
        assertEquals(expectedNew, p.newTotals().caloriesKcal(), 1e-6);
    }

    @Test
    void apply_swapsNamedItem_reusesUnchangedCatalogFood_andMintsNewOne() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        String riceFoodId = entry.ingredients().get(1).foodId();

        AdjustmentProposal p = f.svc.preview(USER, DATE, entry.entryId(), "swap lentils for couscous");
        FoodEntry done = f.svc.apply(USER, DATE, entry.entryId(), accept(p), false);

        assertEquals(EntryAnalysisStatus.READY, done.analysisStatus());
        assertEquals("Pearl couscous and rice", done.foodName());
        assertTrue(done.isComposite());
        assertEquals(2, done.ingredients().size());
        assertEquals("Pearl couscous", done.ingredients().get(0).name());
        assertEquals(riceFoodId, done.ingredients().get(1).foodId(),
            "the unchanged rice keeps its catalog food (and its image)");
        assertNotNull(done.ingredients().get(0).foodId(), "the swapped item gets a fresh catalog food");
        assertNotEquals("food-lentils", done.ingredients().get(0).foodId(),
            "the swapped item does not reuse the removed lentils food");
    }

    @Test
    void preview_requiresAnInstruction() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        assertThrows(IllegalArgumentException.class,
            () -> f.svc.preview(USER, DATE, entry.entryId(), "  "));
    }

    @Test
    void preview_whenAdjusterUnavailable_throwsIllegalState() {
        Fixture f = new Fixture(null);
        FoodEntry entry = f.lentilsAndRice();
        assertThrows(IllegalStateException.class,
            () -> f.svc.preview(USER, DATE, entry.entryId(), "fix it"));
    }

    @Test
    void preview_unknownEntry_throwsIllegalArgument() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        assertThrows(IllegalArgumentException.class,
            () -> f.svc.preview(USER, DATE, "nope", "fix it"));
    }

    // ---- helpers ----

    private static MealAnalysis couscousCorrection() {
        return new MealAnalysis("Pearl couscous and rice", false, List.of(
            new MealItem("Pearl couscous", 150.0,
                new Macros(112.0, 3.8, 23.0, 0.2, 1.4, 0.1), 0.9),
            new MealItem("White rice", 100.0,
                new Macros(130.0, 2.7, 28.0, 0.3, 0.4, 0.1), 0.9)));
    }

    /** Convert a preview proposal into the accepted payload apply expects. */
    private static AcceptedAdjustment accept(AdjustmentProposal p) {
        return new AcceptedAdjustment(p.mealName(), p.packagedProduct(),
            p.items().stream().map(i -> new AcceptedItem(
                i.name(), i.servingLabel(), i.servingGrams(), i.macrosPer100g(), i.macros())).toList());
    }

    private static MealAdjustmentAnalyzer adjuster(MealAnalysis result) {
        return (current, instruction, photoBytes, mimeType) -> result;
    }

    // ---- fixture + fakes ----

    private static final class Fixture {
        final InMemEntries entries = new InMemEntries();
        final NutritionService nutrition =
            new NutritionService(new InMemNutrition(), entries, new MetricChangedPublisher(e -> { }));
        final FoodCatalogService catalog =
            new FoodCatalogService(new FakeCatalogRepo(), 1, empty(), empty());
        final FoodEntryImageService images =
            new FoodEntryImageService(entries, empty(), empty(), empty(),
                new com.gte619n.healthfitness.core.push.SyncChangeNotifier(e -> { }), empty());
        final MealAdjustmentService svc;

        Fixture(MealAdjustmentAnalyzer adjuster) {
            this.svc = new MealAdjustmentService(
                adjuster != null ? provider(adjuster) : empty(),
                empty(), // no photo reader — text-only path
                nutrition, catalog, images,
                null, // MealDescriptionService: unused unless saveAsMeal=true
                new com.gte619n.healthfitness.core.push.SyncChangeNotifier(e -> { }));
        }

        /** A composite "Lentils and rice" entry with catalog-backed ingredients. */
        FoodEntry lentilsAndRice() {
            Macros lentilsPer100g = new Macros(116.0, 9.0, 20.0, 0.4, 8.0, 1.8);
            Macros ricePer100g = new Macros(130.0, 2.7, 28.0, 0.3, 0.4, 0.1);
            List<CompositeIngredient> ings = List.of(
                new CompositeIngredient("Lentils", "food-lentils", lentilsPer100g,
                    150.0, "150 g", 1.0, lentilsPer100g.scale(1.5)),
                new CompositeIngredient("White rice", "food-rice", ricePer100g,
                    100.0, "100 g", 1.0, ricePer100g.scale(1.0)));
            return nutrition.addCompositeMeal(
                USER, DATE, MealType.DINNER, "Lentils and rice", ings, EntrySource.PHOTO);
        }
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
            return foods.stream()
                .filter(f -> f.nameLower() != null && f.nameLower().startsWith(prefixLower))
                .limit(limit)
                .toList();
        }
        @Override public List<CatalogFood> searchByTokens(List<String> queryWords, int limit) {
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
