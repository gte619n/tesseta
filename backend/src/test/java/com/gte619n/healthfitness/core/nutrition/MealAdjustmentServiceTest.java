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
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobQueue;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobType;
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

    // ---- async "Adjust with AI" ----

    @Test
    void startAdjustment_enqueuesJob_andLeavesEntryAdjusting() {
        CapturingQueue queue = new CapturingQueue();
        Fixture f = new Fixture(adjuster(couscousCorrection()), queue);
        FoodEntry entry = f.lentilsAndRice();

        f.svc.startAdjustment(USER, DATE, entry.entryId(), "swap lentils for couscous", true);

        assertEquals(1, queue.jobs.size());
        assertEquals(NutritionJobType.MEAL_ADJUSTMENT, queue.jobs.get(0).type());
        assertEquals(entry.entryId(), queue.jobs.get(0).id());
        MealAdjustment adj = f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow().adjustment();
        assertNotNull(adj);
        assertEquals(AdjustStatus.ADJUSTING, adj.status());
        assertTrue(adj.saveAsMeal());
        assertEquals("swap lentils for couscous", adj.instruction());
    }

    @Test
    void runAdjustment_storesProposal_asPendingReview() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        f.nutrition.beginAdjustment(USER, DATE, entry.entryId(), "swap lentils for couscous", false);

        f.svc.runAdjustment(USER, DATE, entry.entryId());

        MealAdjustment adj = f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow().adjustment();
        assertNotNull(adj);
        assertEquals(AdjustStatus.PENDING_REVIEW, adj.status());
        assertNotNull(adj.proposal());
        assertEquals("Pearl couscous and rice", adj.proposal().mealName());
        // Live macros are untouched until commit.
        assertEquals(entry.macros().caloriesKcal(),
            f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow().macros().caloriesKcal(), 1e-6);
    }

    @Test
    void runAdjustment_whenAdjusterFails_rejects() {
        Fixture f = new Fixture((current, instruction, photoBytes, mimeType) -> {
            throw new IllegalStateException("model down");
        });
        FoodEntry entry = f.lentilsAndRice();
        f.nutrition.beginAdjustment(USER, DATE, entry.entryId(), "fix", false);

        f.svc.runAdjustment(USER, DATE, entry.entryId());

        MealAdjustment adj = f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow().adjustment();
        assertEquals(AdjustStatus.REJECTED, adj.status());
    }

    @Test
    void adjustFromState_isNoOp_whenEntryNotAdjusting() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        // No beginAdjustment → no adjustment state → the queued job is a cheap no-op.
        f.svc.adjustFromStateOrThrow(USER, DATE, entry.entryId());
        assertTrue(f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow().adjustment() == null);
    }

    @Test
    void commit_appliesStoredProposal_andClearsAdjustment() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        f.nutrition.beginAdjustment(USER, DATE, entry.entryId(), "swap lentils for couscous", false);
        f.svc.runAdjustment(USER, DATE, entry.entryId());

        FoodEntry done = f.svc.commit(USER, DATE, entry.entryId());

        assertEquals("Pearl couscous and rice", done.foodName());
        assertEquals("Pearl couscous", done.ingredients().get(0).name());
        assertTrue(done.adjustment() == null, "commit clears the pending adjustment");
    }

    @Test
    void commit_withoutProposal_throwsIllegalState() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        assertThrows(IllegalStateException.class, () -> f.svc.commit(USER, DATE, entry.entryId()));
    }

    @Test
    void discard_clearsPendingAdjustment() {
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        f.nutrition.beginAdjustment(USER, DATE, entry.entryId(), "fix", false);
        f.svc.runAdjustment(USER, DATE, entry.entryId());

        f.svc.discard(USER, DATE, entry.entryId());

        assertTrue(f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow().adjustment() == null);
    }

    // ---- saveAsMeal: review-time override + single-product source update ----

    @Test
    void commit_reviewOverride_singleProduct_updatesOwnedSourceFoodInPlace() {
        Fixture f = new Fixture(adjuster(biscottiCorrection()));
        FoodEntry entry = f.biscotti(USER);
        f.nutrition.beginAdjustment(USER, DATE, entry.entryId(), "the chocolate hazelnut ones", false);
        f.svc.runAdjustment(USER, DATE, entry.entryId());

        // The user flips "also update the saved food" ON at review time.
        FoodEntry done = f.svc.commit(USER, DATE, entry.entryId(), true);

        assertEquals("food-biscotti", done.foodId(), "the entry keeps its source catalog food");
        CatalogFood corrected = f.catalog.get("food-biscotti");
        assertEquals("Chocolate Hazelnut Biscotti", corrected.name());
        assertEquals(60.0, corrected.macrosPer100g().carbsGrams(), 1e-6);
        assertEquals(USER, corrected.createdBy());
    }

    @Test
    void commit_singleProduct_sourceNotOwned_mintsFreshFoodAndLeavesSourceUntouched() {
        Fixture f = new Fixture(adjuster(biscottiCorrection()));
        FoodEntry entry = f.biscotti("someone-else");
        f.nutrition.beginAdjustment(USER, DATE, entry.entryId(), "fix", true);
        f.svc.runAdjustment(USER, DATE, entry.entryId());

        FoodEntry done = f.svc.commit(USER, DATE, entry.entryId());

        assertNotEquals("food-biscotti", done.foodId(),
            "a shared catalog food is never mutated — a fresh one is minted");
        assertEquals("Mini Biscotti", f.catalog.get("food-biscotti").name(),
            "the other user's definition is untouched");
    }

    @Test
    void commit_reviewOverrideFalse_suppressesStoredSaveAsMeal() {
        // The fixture wires no MealDescriptionService (null), so a composite commit
        // that still honored the stored saveAsMeal=true would NPE in saveMeal —
        // completing proves the review-time override suppressed it.
        Fixture f = new Fixture(adjuster(couscousCorrection()));
        FoodEntry entry = f.lentilsAndRice();
        f.nutrition.beginAdjustment(USER, DATE, entry.entryId(), "swap lentils for couscous", true);
        f.svc.runAdjustment(USER, DATE, entry.entryId());

        FoodEntry done = f.svc.commit(USER, DATE, entry.entryId(), false);

        assertEquals("Pearl couscous and rice", done.foodName());
        assertTrue(done.adjustment() == null, "commit clears the pending adjustment");
    }

    // ---- helpers ----

    private static MealAnalysis biscottiCorrection() {
        return new MealAnalysis("Chocolate Hazelnut Biscotti", true, List.of(
            new MealItem("Chocolate Hazelnut Biscotti", 30.0,
                new Macros(498.0, 6.0, 60.0, 26.0, 3.0, 30.0), 0.9)));
    }

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
            this(adjuster, null);
        }

        Fixture(MealAdjustmentAnalyzer adjuster, NutritionJobQueue queue) {
            this.svc = new MealAdjustmentService(
                adjuster != null ? provider(adjuster) : empty(),
                empty(), // no photo reader — text-only path
                nutrition, catalog, images,
                null, // MealDescriptionService: unused unless saveAsMeal=true
                new com.gte619n.healthfitness.core.push.SyncChangeNotifier(e -> { }),
                queue != null ? provider(queue) : empty(),
                new com.gte619n.healthfitness.core.nutrition.AdjustReviewPublisher(e -> { }));
        }

        /**
         * A single packaged-product entry backed by catalog food
         * {@code food-biscotti} created by {@code createdBy}.
         */
        FoodEntry biscotti(String createdBy) {
            Macros per100g = new Macros(480.0, 5.0, 65.0, 22.0, 2.0, 28.0);
            catalog.create(createdBy, "Mini Biscotti", null, null, "product", per100g,
                List.of(new ServingSize("30 g", 30.0)), 0, FoodSource.GEMINI_PHOTO,
                null, "food-biscotti");
            return nutrition.addEntry(USER, DATE, MealType.DINNER, "food-biscotti",
                "Mini Biscotti", "30 g", 30.0, 1.0, per100g.scale(0.3), EntrySource.PHOTO);
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

    /** Captures enqueued jobs without running them (a durable queue never runs inline). */
    private static final class CapturingQueue implements NutritionJobQueue {
        final List<NutritionJob> jobs = new java.util.ArrayList<>();
        @Override public void enqueue(NutritionJob job) { jobs.add(job); }
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
        @Override public void save(CatalogFood food) {
            foods.removeIf(f -> f.foodId().equals(food.foodId()));
            foods.add(food);
        }
        @Override public void saveConfirmation(String foodId, String userId) { }
        @Override public int countConfirmations(String foodId) { return 0; }
    }
}
