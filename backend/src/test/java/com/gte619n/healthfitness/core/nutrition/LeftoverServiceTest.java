package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.nutrition.LeftoverAnalyzer.LeftoverEstimate;
import com.gte619n.healthfitness.core.nutrition.LeftoverAnalyzer.LeftoverEstimate.ItemEstimate;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobQueue;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link LeftoverService} end-to-end (analyze → apply/discard/
 * restore) with in-memory fakes and a fake analyzer (IMPL-LEFTOVER-01 §6.1).
 * Proves the served baseline is preserved, day totals drop by served−consumed,
 * re-run recomputes from the baseline, and reject leaves macros untouched.
 */
class LeftoverServiceTest {

    private static final String USER = "u-lo";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 12);

    private static final Macros RICE_100 = new Macros(null, 2.7, 28.0, 0.3, 0.4, 0.1);
    private static final Macros SALMON_100 = new Macros(null, 20.0, 0.0, 13.0, 0.0, 0.0);

    @Test
    void analyzeThenApply_setsConsumed_preservesServed_dropsDayTotal() {
        Fixture f = new Fixture(estimate(75.0, 20.0, 0.9)); // ate rice 75, salmon 120
        FoodEntry entry = f.compositeMeal();
        double servedKcal = dayTotalKcal(f);

        FoodEntry analyzing = f.svc.startAnalysis(USER, DATE, entry.entryId(), leftoverJpeg(), "image/jpeg");
        assertEquals(LeftoverStatus.ANALYZING, analyzing.leftover().status());
        assertEquals(servedKcal, analyzing.leftover().servedMacros().caloriesKcal(), 1e-6);
        assertEquals(servedKcal, dayTotalKcal(f), 1e-6, "nothing changes until apply");

        NutritionJob job = f.queue.jobs.get(0);
        f.svc.analyzeFromRefOrThrow(USER, DATE, entry.entryId(), job.ref(), job.mime());

        FoodEntry reviewing = f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow();
        assertEquals(LeftoverStatus.PENDING_REVIEW, reviewing.leftover().status());
        assertNotNull(reviewing.leftover().proposal());
        assertEquals(servedKcal, dayTotalKcal(f), 1e-6, "pending review does not change totals");

        FoodEntry applied = f.svc.apply(USER, DATE, entry.entryId());
        double consumedKcal = RICE_100.scale(0.75).withDerivedCalories().caloriesKcal()
            + SALMON_100.scale(1.2).withDerivedCalories().caloriesKcal();
        assertEquals(LeftoverStatus.APPLIED, applied.leftover().status());
        assertEquals(consumedKcal, applied.macros().caloriesKcal(), 1e-6);
        assertEquals(consumedKcal, dayTotalKcal(f), 1e-6);
        assertEquals(servedKcal, applied.leftover().servedMacros().caloriesKcal(), 1e-6,
            "served baseline preserved after apply");
        assertTrue(dayTotalKcal(f) < servedKcal);
        assertTrue(f.storage.isEmpty(), "leftover photo discarded after analysis (D11)");
    }

    @Test
    void rerun_recomputesFromServedBaseline_notFromConsumed() {
        Fixture f = new Fixture(estimate(75.0, 20.0, 0.9));
        FoodEntry entry = f.compositeMeal();
        double servedKcal = dayTotalKcal(f);

        // First pass: eat rice 75 / salmon 120, apply.
        f.svc.startAnalysis(USER, DATE, entry.entryId(), leftoverJpeg(), "image/jpeg");
        runQueued(f, entry.entryId());
        f.svc.apply(USER, DATE, entry.entryId());

        // Re-run with a DIFFERENT estimate (ate everything now).
        f.analyzer.set(estimate(0.0, 0.0, 0.9));
        FoodEntry reanalyzing = f.svc.startAnalysis(USER, DATE, entry.entryId(), leftoverJpeg(), "image/jpeg");
        assertEquals(servedKcal, reanalyzing.leftover().servedMacros().caloriesKcal(), 1e-6,
            "re-run keeps the original served baseline");
        runQueued(f, entry.entryId());
        FoodEntry applied = f.svc.apply(USER, DATE, entry.entryId());

        // Consumed == full served (remaining 0), computed from the served baseline.
        assertEquals(servedKcal, applied.macros().caloriesKcal(), 1e-6);
        assertEquals(servedKcal, dayTotalKcal(f), 1e-6);
    }

    @Test
    void restore_resetsToServed_clearsLeftover() {
        Fixture f = new Fixture(estimate(75.0, 20.0, 0.9));
        FoodEntry entry = f.compositeMeal();
        double servedKcal = dayTotalKcal(f);

        f.svc.startAnalysis(USER, DATE, entry.entryId(), leftoverJpeg(), "image/jpeg");
        runQueued(f, entry.entryId());
        f.svc.apply(USER, DATE, entry.entryId());
        assertTrue(dayTotalKcal(f) < servedKcal);

        FoodEntry restored = f.svc.restore(USER, DATE, entry.entryId());
        assertNull(restored.leftover(), "restore clears the leftover");
        assertEquals(servedKcal, restored.macros().caloriesKcal(), 1e-6);
        assertEquals(servedKcal, dayTotalKcal(f), 1e-6);
    }

    @Test
    void reject_leavesMacrosUntouched() {
        Fixture f = new Fixture(new LeftoverEstimate(List.of(), 0.9)); // empty → reject
        FoodEntry entry = f.compositeMeal();
        double servedKcal = dayTotalKcal(f);

        f.svc.startAnalysis(USER, DATE, entry.entryId(), leftoverJpeg(), "image/jpeg");
        runQueued(f, entry.entryId());

        FoodEntry after = f.nutrition.findEntry(USER, DATE, entry.entryId()).orElseThrow();
        assertEquals(LeftoverStatus.REJECTED, after.leftover().status());
        assertEquals(servedKcal, after.macros().caloriesKcal(), 1e-6);
        assertEquals(servedKcal, dayTotalKcal(f), 1e-6);
        assertTrue(f.storage.isEmpty(), "rejected leftover photo still discarded (D11)");
    }

    @Test
    void discardPendingReview_firstTime_clearsLeftover() {
        Fixture f = new Fixture(estimate(75.0, 20.0, 0.9));
        FoodEntry entry = f.compositeMeal();
        double servedKcal = dayTotalKcal(f);

        f.svc.startAnalysis(USER, DATE, entry.entryId(), leftoverJpeg(), "image/jpeg");
        runQueued(f, entry.entryId());
        FoodEntry discarded = f.svc.discard(USER, DATE, entry.entryId()).orElseThrow();

        assertNull(discarded.leftover());
        assertEquals(servedKcal, discarded.macros().caloriesKcal(), 1e-6);
    }

    @Test
    void ineligibleEntry_isRejected() {
        Fixture f = new Fixture(estimate(75.0, 20.0, 0.9));
        FoodEntry single = f.nutrition.addEntry(
            USER, DATE, MealType.SNACK, "food-x", "Apple", "1", 100.0, 1.0,
            new Macros(52.0, 0.3, 14.0, 0.2, 2.4, 10.0), EntrySource.MANUAL);
        assertThrows(IllegalStateException.class,
            () -> f.svc.startAnalysis(USER, DATE, single.entryId(), leftoverJpeg(), "image/jpeg"));
    }

    // ---- helpers ----

    private static void runQueued(Fixture f, String entryId) {
        NutritionJob job = f.queue.jobs.get(f.queue.jobs.size() - 1);
        f.svc.analyzeFromRefOrThrow(USER, DATE, entryId, job.ref(), job.mime());
    }

    private static double dayTotalKcal(Fixture f) {
        return f.nutrition.findByDate(USER, DATE).map(NutritionDailyLog::caloriesKcal).orElse(0.0);
    }

    private static byte[] leftoverJpeg() {
        return new byte[]{9, 9, 9};
    }

    private static LeftoverEstimate estimate(double riceLeft, double salmonLeft, double conf) {
        return new LeftoverEstimate(List.of(
            new ItemEstimate("rice", riceLeft, true, conf),
            new ItemEstimate("salmon", salmonLeft, true, conf)), conf);
    }

    // ---- fixture + fakes ----

    private static final class Fixture {
        final InMemEntries entries = new InMemEntries();
        final NutritionService nutrition =
            new NutritionService(new InMemNutrition(), entries, new MetricChangedPublisher(e -> { }));
        final MutableAnalyzer analyzer;
        final RecordingQueue queue = new RecordingQueue();
        final FakePhotoStorage storage = new FakePhotoStorage();
        final LeftoverService svc;

        Fixture(LeftoverEstimate initial) {
            this.analyzer = new MutableAnalyzer(initial);
            this.svc = new LeftoverService(
                provider(analyzer),
                provider((MealPhotoStore) storage),
                provider((MealPhotoReader) storage),
                nutrition,
                new SyncChangeNotifier(e -> { }),
                provider((NutritionJobQueue) queue),
                new LeftoverReviewPublisher(e -> { }));
        }

        FoodEntry compositeMeal() {
            // A composite entry WITH a retained original photo (leftover-eligible).
            String photoRef = storage.store(USER, new byte[]{1, 2, 3}, "image/jpeg");
            FoodEntry placeholder = nutrition.beginAnalyzingEntry(USER, DATE, MealType.DINNER, photoRef);
            List<CompositeIngredient> ings = List.of(
                ing("rice", RICE_100, 150.0), ing("salmon", SALMON_100, 140.0));
            return nutrition.finalizeCompositeMeal(
                USER, DATE, placeholder.entryId(), "Salmon and rice", ings);
        }

        private static CompositeIngredient ing(String name, Macros per100, double grams) {
            return new CompositeIngredient(
                name, "food-" + name, per100, grams, grams + " g", 1.0, per100.scale(grams / 100.0));
        }
    }

    private static final class MutableAnalyzer implements LeftoverAnalyzer {
        private volatile LeftoverEstimate estimate;
        MutableAnalyzer(LeftoverEstimate e) { this.estimate = e; }
        void set(LeftoverEstimate e) { this.estimate = e; }
        @Override public LeftoverEstimate estimate(
            ServedMeal served, byte[] originalPhoto, String originalMime,
            byte[] leftoverPhoto, String leftoverMime) {
            return estimate;
        }
    }

    private static final class RecordingQueue implements NutritionJobQueue {
        final List<NutritionJob> jobs = new ArrayList<>();
        @Override public void enqueue(NutritionJob job) { jobs.add(job); }
    }

    /** A shared in-memory store+reader keyed by a synthetic ref. */
    private static final class FakePhotoStorage implements MealPhotoStore, MealPhotoReader {
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        private int seq = 0;
        @Override public synchronized String store(String userId, byte[] bytes, String mime) {
            String ref = "ref://" + (seq++);
            blobs.put(ref, bytes);
            return ref;
        }
        @Override public void delete(String ref) { if (ref != null) blobs.remove(ref); }
        @Override public Optional<Photo> read(String ref) {
            byte[] b = blobs.get(ref);
            return b == null ? Optional.empty() : Optional.of(new Photo(b, "image/jpeg"));
        }
        boolean isEmpty() { return blobs.size() <= 1; } // only the original remains
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
}
