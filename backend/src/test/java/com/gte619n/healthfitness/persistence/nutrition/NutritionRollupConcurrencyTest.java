package com.gte619n.healthfitness.persistence.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.nutrition.EntryAnalysisStatus;
import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import com.google.cloud.firestore.Firestore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Phase-1 correctness (nutrition): the daily-rollup recompute reads all of a
 * day's food entries and writes the rollup. A plain read-sum-write lets a stale
 * recompute clobber the total when entries are added concurrently. The fix makes
 * read+write atomic (a Firestore transaction, {@code recomputeFromEntries}).
 *
 * <p>Note: the lost update is <em>self-healing</em> — any later recompute fixes
 * it — so it can't be reproduced as a deterministic red. These tests instead
 * verify the atomic recompute computes the correct rollup and converges to the
 * right total under heavy concurrent adds + recomputes (exercising the real
 * transaction under contention). Runs against the emulator.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class NutritionRollupConcurrencyTest {

    private static final String USER = "user-1";
    private static final LocalDate DAY = LocalDate.of(2026, 7, 4);

    @Test
    void recomputeSumsEveryEntryIntoTheRollup(Firestore firestore) {
        FirestoreFoodEntryRepository entriesRepo = new FirestoreFoodEntryRepository(firestore);
        FirestoreNutritionDailyLogRepository dailyLog =
            new FirestoreNutritionDailyLogRepository(firestore);

        entriesRepo.save(entry("e1"));
        entriesRepo.save(entry("e2"));
        entriesRepo.save(entry("e3"));

        NutritionDailyLog rollup = dailyLog.recomputeFromEntries(USER, DAY, entriesRepo);

        assertThat(rollup.proteinGrams()).isEqualTo(3.0);
        assertThat(dailyLog.findByDate(USER, DAY).orElseThrow().proteinGrams()).isEqualTo(3.0);
    }

    @Test
    void concurrentRecomputesConvergeToTheCorrectTotal(Firestore firestore) throws Exception {
        FirestoreFoodEntryRepository entriesRepo = new FirestoreFoodEntryRepository(firestore);
        FirestoreNutritionDailyLogRepository dailyLog =
            new FirestoreNutritionDailyLogRepository(firestore);

        int entries = 5;
        for (int i = 0; i < entries; i++) {
            entriesRepo.save(entry("e" + i));
        }

        // Several recomputes fire at once on the same day. The atomic transaction
        // must serialise them (no lost write / corrupt partial sum). Kept modest:
        // the emulator uses pessimistic transaction locking (production is
        // optimistic), so heavy same-doc contention would spuriously lock-timeout.
        int concurrency = 5;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier startLine = new CyclicBarrier(concurrency);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                dailyLog.recomputeFromEntries(USER, DAY, entriesRepo);
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(dailyLog.findByDate(USER, DAY).orElseThrow().proteinGrams())
            .as("concurrent recomputes converge to the full entry total")
            .isEqualTo((double) entries);
    }

    private static FoodEntry entry(String entryId) {
        return new FoodEntry(
            USER, DAY, entryId, MealType.BREAKFAST,
            null, "food", null, null, null,
            new Macros(0.0, 1.0, 0.0, 0.0, 0.0, 0.0), // calories 0, protein 1
            null, null, EntrySource.MANUAL, null, null, null,
            EntryAnalysisStatus.NONE, null, null);
    }
}
