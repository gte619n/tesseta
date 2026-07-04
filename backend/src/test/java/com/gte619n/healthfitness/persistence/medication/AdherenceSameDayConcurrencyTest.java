package com.gte619n.healthfitness.persistence.medication;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.DoseLog;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Phase-1 correctness: logging two doses for DIFFERENT windows on the same day
 * concurrently must not lose one. The historical path (findByDate + mutate list +
 * overwrite) is a read-modify-write with no isolation, so the second save clobbers
 * the first. {@code upsertDose} makes it atomic (a Firestore transaction). Runs
 * against the real emulator.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class AdherenceSameDayConcurrencyTest {

    private static final String USER = "user-1";
    private static final String MED = "med-1";
    private static final LocalDate DAY = LocalDate.of(2026, 7, 4);

    @Test
    void concurrentDosesForDifferentWindowsBothSurvive(Firestore firestore) throws Exception {
        AdherenceRepositoryImpl repo = new AdherenceRepositoryImpl(firestore);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable morning = doseTask(repo, TimeWindow.MORNING, go);
        Runnable evening = doseTask(repo, TimeWindow.EVENING, go);
        pool.submit(morning);
        pool.submit(evening);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        AdherenceLog log = repo.findByDate(USER, MED, DAY).orElseThrow();
        List<TimeWindow> windows = log.doses().stream().map(DoseLog::window).sorted().toList();
        assertThat(windows)
            .as("both concurrently-logged windows must survive")
            .containsExactly(TimeWindow.MORNING, TimeWindow.EVENING);
    }

    private Runnable doseTask(AdherenceRepositoryImpl repo, TimeWindow window, CountDownLatch go) {
        return () -> {
            try {
                go.await();
                repo.upsertDose(USER, MED, DAY, new DoseLog(window, Instant.now(), 100.0), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
