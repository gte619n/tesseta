package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.nutrition.MealDescriptionService.MealResolution;
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
 * Unit-tests the deterministic resolve/log logic of {@link MealDescriptionService}
 * with in-memory fakes (no Gemini, no GCS): a fresh description creates and saves
 * a new meal, a description matching a saved meal reuses it, and logging a
 * resolved meal writes a composite entry with the full ingredient breakdown.
 */
class MealDescriptionServiceTest {

    private static final String USER = "u-desc";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 5);

    @Test
    void resolve_noMatch_createsAndSavesNewMeal() {
        Fixture f = new Fixture(analyzer(
            new MealAnalysis("Chicken, rice and broccoli", false, List.of(
                new MealItem("Grilled chicken breast", 150.0,
                    new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0), 0.9),
                new MealItem("White rice", 200.0,
                    new Macros(130.0, 2.7, 28.0, 0.3, 0.4, 0.1), 0.85))),
            Optional.empty()));

        MealResolution r = f.svc.resolve(USER, "grilled chicken with rice and broccoli");

        assertFalse(r.matched(), "nothing in the catalog yet, so it's a new meal");
        assertEquals("Chicken, rice and broccoli", r.name());
        assertEquals(2, r.ingredients().size());
        assertTrue(f.meals.findById(r.mealId()).isPresent(), "the new meal is saved for reuse");
        // 150 g chicken (247.5 kcal) + 200 g rice (260 kcal)
        assertEquals(507.5, r.macros().caloriesKcal(), 1e-6);
    }

    @Test
    void resolve_match_reusesExistingMeal_withoutCreatingAnother() {
        SavedMeal existing = new SavedMeal(
            "meal-1", "Chicken, rice and broccoli", "chicken, rice and broccoli", USER,
            List.of(new CompositeIngredient("Grilled chicken breast", null,
                new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0), 150.0, "150 g", 1.0,
                new Macros(247.5, 46.5, 0.0, 5.4, 0.0, 0.0))),
            150.0, new Macros(247.5, 46.5, 0.0, 5.4, 0.0, 0.0),
            FoodSource.GEMINI_DESCRIPTION, "http://img/meal-1.png", FoodImageStatus.READY, null, null);
        Fixture f = new Fixture(analyzer(
            new MealAnalysis("Chicken, rice and broccoli", false, List.of(
                new MealItem("Grilled chicken breast", 150.0,
                    new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0), 0.9))),
            Optional.of("meal-1")));
        f.meals.save(existing);

        MealResolution r = f.svc.resolve(USER, "chicken and rice");

        assertTrue(r.matched(), "the saved meal is reused");
        assertEquals("meal-1", r.mealId());
        assertEquals(1, f.meals.all().size(), "no second meal is created on a match");
    }

    @Test
    void logResolvedMeal_writesCompositeEntry_andReusesReadyPhoto() {
        SavedMeal saved = new SavedMeal(
            "meal-2", "Salmon and broccoli", "salmon and broccoli", USER,
            List.of(
                new CompositeIngredient("Grilled salmon", null,
                    new Macros(208.0, 20.0, 0.0, 13.0, 0.0, 0.0), 200.0, "200 g", 1.0,
                    new Macros(416.0, 40.0, 0.0, 26.0, 0.0, 0.0)),
                new CompositeIngredient("Steamed broccoli", null,
                    new Macros(35.0, 2.4, 7.0, 0.4, 3.3, 1.5), 100.0, "100 g", 1.0,
                    new Macros(35.0, 2.4, 7.0, 0.4, 3.3, 1.5))),
            300.0, new Macros(451.0, 42.4, 7.0, 26.4, 3.3, 1.5),
            FoodSource.GEMINI_DESCRIPTION, "http://img/meal-2.png", FoodImageStatus.READY, null, null);
        Fixture f = new Fixture(analyzer(new MealAnalysis(null, false, List.of()), Optional.empty()));
        f.meals.save(saved);

        FoodEntry entry = f.svc.logResolvedMeal(USER, DATE, MealType.DINNER, "meal-2");

        FoodEntry stored = f.entries.findById(USER, DATE, entry.entryId()).orElseThrow();
        assertTrue(stored.isComposite());
        assertEquals(2, stored.ingredients().size(), "full ingredient breakdown is preserved");
        // Calories derived from the summed macros: 42.4·4 + 7·4 + 26.4·9 = 435.2.
        assertEquals(42.4 * 4 + 7.0 * 4 + 26.4 * 9, stored.macros().caloriesKcal(), 1e-6);
        assertEquals("http://img/meal-2.png", stored.mealImageUrl(),
            "a READY saved-meal photo is reused, not regenerated");
        assertEquals(FoodImageStatus.READY, stored.mealImageStatus());
    }

    @Test
    void logResolvedMeal_honoursCallerSuppliedEntryId_forIdempotentReplay() {
        SavedMeal saved = new SavedMeal(
            "meal-3", "Eggs", "eggs", USER,
            List.of(new CompositeIngredient("Eggs", null,
                new Macros(155.0, 13.0, 1.1, 11.0, 0.0, 1.1), 100.0, "100 g", 1.0,
                new Macros(155.0, 13.0, 1.1, 11.0, 0.0, 1.1))),
            100.0, new Macros(155.0, 13.0, 1.1, 11.0, 0.0, 1.1),
            FoodSource.GEMINI_DESCRIPTION, null, FoodImageStatus.NONE, null, null);
        Fixture f = new Fixture(analyzer(new MealAnalysis(null, false, List.of()), Optional.empty()));
        f.meals.save(saved);

        FoodEntry entry = f.svc.logResolvedMeal(
            USER, DATE, MealType.DINNER, "meal-3", "client-entry-123");

        assertEquals("client-entry-123", entry.entryId(),
            "the composite entry uses the caller-minted id so a replay reuses it");
        assertTrue(f.entries.findById(USER, DATE, "client-entry-123").isPresent());
    }

    @Test
    void describeAsync_honoursCallerSuppliedEntryId_forIdempotentReplay() {
        Fixture f = new Fixture(analyzer(
            new MealAnalysis("Oatmeal", false, List.of(
                new MealItem("Oatmeal", 250.0,
                    new Macros(68.0, 2.4, 12.0, 1.4, 1.7, 0.5), 0.9))),
            Optional.empty()));

        FoodEntry placeholder = f.svc.describeMealAsync(
            USER, DATE, MealType.BREAKFAST, "a bowl of oatmeal", "client-entry-async-9");

        assertEquals("client-entry-async-9", placeholder.entryId(),
            "the ANALYZING placeholder uses the caller-minted id so a replay reuses it");
    }

    @Test
    void logDescribed_oneShot_resolvesThenLogs() {
        Fixture f = new Fixture(analyzer(
            new MealAnalysis("Oatmeal", false, List.of(
                new MealItem("Oatmeal", 250.0,
                    new Macros(68.0, 2.4, 12.0, 1.4, 1.7, 0.5), 0.9))),
            Optional.empty()));

        FoodEntry entry = f.svc.logDescribed(USER, DATE, MealType.BREAKFAST, "a bowl of oatmeal");

        FoodEntry stored = f.entries.findById(USER, DATE, entry.entryId()).orElseThrow();
        assertEquals("Oatmeal", stored.foodName());
        assertNotNull(stored.ingredients());
        assertEquals(1, f.meals.all().size(), "the one-shot path also saves the meal for reuse");
    }

    @Test
    void describeAsync_logsPlaceholderImmediately_thenFinalizesComposite() {
        Fixture f = new Fixture(analyzer(
            new MealAnalysis("Oatmeal", false, List.of(
                new MealItem("Oatmeal", 250.0,
                    new Macros(68.0, 2.4, 12.0, 1.4, 1.7, 0.5), 0.9))),
            Optional.empty()));

        FoodEntry placeholder = f.nutrition.beginAnalyzingEntry(
            USER, DATE, MealType.BREAKFAST, null, null, "a bowl of oatmeal", EntrySource.MANUAL);
        assertTrue(placeholder.isAnalyzing());
        assertEquals("a bowl of oatmeal", placeholder.foodName(),
            "the pending row reads as what the user typed");

        f.svc.resolveAndFinalize(USER, DATE, placeholder.entryId(), "a bowl of oatmeal");

        FoodEntry done = f.entries.findById(USER, DATE, placeholder.entryId()).orElseThrow();
        assertEquals(EntryAnalysisStatus.READY, done.analysisStatus());
        assertEquals("Oatmeal", done.foodName());
        assertTrue(done.isComposite());
        assertEquals(1, f.meals.all().size(), "the async path also saves the meal for reuse");
    }

    @Test
    void describeAsync_unidentifiableDescription_marksFailed_keepingTheDescription() {
        Fixture f = new Fixture(analyzer(
            new MealAnalysis(null, false, List.of()), Optional.empty()));

        FoodEntry placeholder = f.nutrition.beginAnalyzingEntry(
            USER, DATE, MealType.LUNCH, null, null, "asdfghjkl", EntrySource.MANUAL);
        f.svc.resolveAndFinalize(USER, DATE, placeholder.entryId(), "asdfghjkl");

        FoodEntry done = f.entries.findById(USER, DATE, placeholder.entryId()).orElseThrow();
        assertEquals(EntryAnalysisStatus.FAILED, done.analysisStatus());
        assertEquals("asdfghjkl", done.foodName(),
            "a described placeholder keeps the user's text on failure (not 'Couldn’t read photo')");
    }

    @Test
    void describeAsync_matchedMeal_reusesItsReadyPhoto() {
        SavedMeal saved = new SavedMeal(
            "meal-9", "Oatmeal", "oatmeal", USER,
            List.of(new CompositeIngredient("Oatmeal", null,
                new Macros(68.0, 2.4, 12.0, 1.4, 1.7, 0.5), 250.0, "250 g", 1.0,
                new Macros(170.0, 6.0, 30.0, 3.5, 4.25, 1.25))),
            250.0, new Macros(170.0, 6.0, 30.0, 3.5, 4.25, 1.25),
            FoodSource.GEMINI_DESCRIPTION, "http://img/meal-9.png", FoodImageStatus.READY, null, null);
        Fixture f = new Fixture(analyzer(
            new MealAnalysis("Oatmeal", false, List.of(
                new MealItem("Oatmeal", 250.0,
                    new Macros(68.0, 2.4, 12.0, 1.4, 1.7, 0.5), 0.9))),
            Optional.of("meal-9")));
        f.meals.save(saved);

        FoodEntry placeholder = f.nutrition.beginAnalyzingEntry(
            USER, DATE, MealType.BREAKFAST, null, null, "oatmeal", EntrySource.MANUAL);
        f.svc.resolveAndFinalize(USER, DATE, placeholder.entryId(), "oatmeal");

        FoodEntry done = f.entries.findById(USER, DATE, placeholder.entryId()).orElseThrow();
        assertEquals(EntryAnalysisStatus.READY, done.analysisStatus());
        assertEquals("http://img/meal-9.png", done.mealImageUrl(),
            "a matched meal's READY photo is attached, not regenerated");
        assertEquals(1, f.meals.all().size(), "no duplicate meal is created on a match");
    }

    @Test
    void searchSavedMeals_dedupesDuplicatesKeepingMostSpecific_andHidesArchived() {
        Fixture f = new Fixture(analyzer(new MealAnalysis(null, false, List.of()), Optional.empty()));
        // Three "Vanilla Protein Shake" rows minted by repeated describes — same
        // name, same calories. Only "vshake-b" has a photo, both ingredients and
        // a full macro breakdown, so it's the most specific.
        f.meals.save(shake("vshake-a", 1, FoodImageStatus.NONE, null,
            new Macros(250.0, null, null, null, null, null)));
        f.meals.save(shake("vshake-b", 2, FoodImageStatus.READY, "http://img/b.png",
            new Macros(250.0, 30.0, 12.0, 8.0, 1.0, 20.0)));
        f.meals.save(shake("vshake-c", 1, FoodImageStatus.NONE, null,
            new Macros(250.0, null, null, null, null, null)));

        List<SavedMeal> results = f.svc.searchSavedMeals(USER, "Vanilla Protein Shake");

        assertEquals(1, results.size(), "the five-way duplicate collapses to one");
        assertEquals("vshake-b", results.get(0).mealId(), "the most complete row wins");

        // Archiving the survivor hides the whole dish; the catalog docs remain.
        f.svc.archiveMeal(USER, "vshake-b");
        List<SavedMeal> afterArchive = f.svc.searchSavedMeals(USER, "Vanilla Protein Shake");
        assertEquals(1, afterArchive.size(), "a lesser duplicate takes over once the survivor is hidden");
        assertFalse(afterArchive.get(0).mealId().equals("vshake-b"), "the archived meal no longer shows");
        assertEquals(3, f.meals.all().size(), "archiving never deletes the shared catalog docs");
    }

    private static SavedMeal shake(
        String id, int ingredientCount, FoodImageStatus imageStatus, String imageUrl, Macros macros) {
        List<CompositeIngredient> ingredients = new java.util.ArrayList<>();
        for (int i = 0; i < ingredientCount; i++) {
            ingredients.add(new CompositeIngredient("ingredient-" + i, null,
                new Macros(100.0, 10.0, 5.0, 3.0, 0.0, 5.0), 100.0, "100 g", 1.0,
                new Macros(100.0, 10.0, 5.0, 3.0, 0.0, 5.0)));
        }
        return new SavedMeal(id, "Vanilla Protein Shake", "vanilla protein shake", USER,
            ingredients, null, macros, FoodSource.GEMINI_DESCRIPTION, imageUrl, imageStatus, null, null);
    }

    // ---- fixture + fakes ----

    private static final class Fixture {
        final InMemEntries entries = new InMemEntries();
        final InMemSavedMeals meals = new InMemSavedMeals();
        final InMemArchivedMeals archived = new InMemArchivedMeals();
        final NutritionService nutrition =
            new NutritionService(new InMemNutrition(), entries, new MetricChangedPublisher(e -> { }));
        final SavedMealImageService mealImages =
            new SavedMealImageService(meals, empty(), empty());
        final FoodEntryImageService entryImages =
            new FoodEntryImageService(entries, empty(), empty(), empty(),
                new com.gte619n.healthfitness.core.push.SyncChangeNotifier(event -> { }));
        final MealDescriptionService svc;

        Fixture(MealDescriptionAnalyzer analyzer) {
            this.svc = new MealDescriptionService(
                provider(analyzer), meals, archived, mealImages, nutrition, entryImages,
                new com.gte619n.healthfitness.core.push.SyncChangeNotifier(event -> { }));
        }
    }

    private static MealDescriptionAnalyzer analyzer(MealAnalysis analysis, Optional<String> match) {
        return new MealDescriptionAnalyzer() {
            @Override public MealAnalysis analyze(String description) { return analysis; }
            @Override public Optional<String> matchMeal(String description, List<MealCandidate> candidates) {
                return match;
            }
        };
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
            return Optional.empty();
        }
        @Override public void save(FoodEntry entry) { rows.put(key(entry.date(), entry.entryId()), entry); }
        @Override public void delete(String userId, LocalDate date, String entryId) { rows.remove(key(date, entryId)); }
    }

    private static final class InMemSavedMeals implements SavedMealRepository {
        private final Map<String, SavedMeal> rows = new ConcurrentHashMap<>();
        List<SavedMeal> all() { return List.copyOf(rows.values()); }
        @Override public Optional<SavedMeal> findById(String mealId) {
            return Optional.ofNullable(rows.get(mealId));
        }
        @Override public List<SavedMeal> searchByNamePrefix(String prefixLower, int limit) {
            return rows.values().stream()
                .filter(m -> m.nameLower() != null && m.nameLower().startsWith(prefixLower))
                .limit(limit)
                .toList();
        }
        @Override public void save(SavedMeal meal) { rows.put(meal.mealId(), meal); }
        @Override public List<SavedMeal> findByImageStatus(FoodImageStatus status, int limit) {
            return rows.values().stream().filter(m -> m.imageStatus() == status).limit(limit).toList();
        }
    }

    private static final class InMemArchivedMeals implements ArchivedMealRepository {
        private final Map<String, java.util.Set<String>> rows = new ConcurrentHashMap<>();
        @Override public void archive(String userId, String mealId) {
            rows.computeIfAbsent(userId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(mealId);
        }
        @Override public java.util.Set<String> archivedMealIds(String userId) {
            return rows.getOrDefault(userId, java.util.Set.of());
        }
    }
}
