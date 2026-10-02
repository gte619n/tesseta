package com.gte619n.healthfitness.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.ai.AiFeature;
import com.gte619n.healthfitness.core.ai.AiUsageEvent;
import com.gte619n.healthfitness.core.ai.AiUsageSummary;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Emulator-backed tests for {@link FirestoreAiUsageStore}: a recorded event
 * folds into BOTH the per-user and global monthly rollups (totals + per-feature
 * map), reads reconstitute the summary, top-spenders orders by cost, and
 * concurrent increments converge (no lost updates).
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class FirestoreAiUsageStoreTest {

    // Fixed month so doc ids are deterministic regardless of wall clock.
    private static final Instant JAN = Instant.parse("2026-01-15T12:00:00Z");
    private static final String MONTH = "2026-01";

    private static AiUsageEvent event(String user, AiFeature feature,
        long in, long out, long images, double cost) {
        return new AiUsageEvent(
            UUID.randomUUID().toString(), user, feature, "gemini-3.8-flash",
            in, out, images, cost, AiUsageEvent.Status.SUCCESS,
            JAN, JAN, false, null);
    }

    @Test
    void recordEventFoldsIntoUserAndGlobalRollups(Firestore firestore) {
        FirestoreAiUsageStore store = new FirestoreAiUsageStore(firestore);

        store.recordEvent(event("u1", AiFeature.MEAL_PHOTO, 100, 50, 0, 0.5));
        store.recordEvent(event("u1", AiFeature.FOOD_IMAGE_GEN, 10, 0, 1, 0.04));

        AiUsageSummary user = store.monthlyForUser("u1", MONTH).orElseThrow();
        assertThat(user.scope()).isEqualTo("u1");
        assertThat(user.totalCalls()).isEqualTo(2);
        assertThat(user.totalInputTokens()).isEqualTo(110);
        assertThat(user.totalOutputTokens()).isEqualTo(50);
        assertThat(user.totalImages()).isEqualTo(1);
        assertThat(user.totalCostUsd()).isCloseTo(0.54, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(user.byFeature()).containsKeys(AiFeature.MEAL_PHOTO, AiFeature.FOOD_IMAGE_GEN);
        assertThat(user.byFeature().get(AiFeature.MEAL_PHOTO).calls()).isEqualTo(1);

        AiUsageSummary global = store.monthlyGlobal(MONTH).orElseThrow();
        assertThat(global.scope()).isEqualTo(AiUsageSummary.GLOBAL);
        assertThat(global.totalCalls()).isEqualTo(2);
        assertThat(global.totalCostUsd()).isCloseTo(0.54, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void topSpendersOrdersUsersByCostDescending(Firestore firestore) {
        FirestoreAiUsageStore store = new FirestoreAiUsageStore(firestore);

        store.recordEvent(event("low", AiFeature.MEAL_PHOTO, 100, 10, 0, 0.10));
        store.recordEvent(event("high", AiFeature.GOAL_CHAT, 1000, 500, 0, 5.00));
        store.recordEvent(event("mid", AiFeature.DRINK, 500, 100, 0, 1.00));

        List<AiUsageSummary> top = store.topSpenders(MONTH, 10);
        assertThat(top).hasSize(3);
        assertThat(top.get(0).scope()).isEqualTo("high");
        assertThat(top.get(1).scope()).isEqualTo("mid");
        assertThat(top.get(2).scope()).isEqualTo("low");
    }

    @Test
    void missingMonthReturnsEmpty(Firestore firestore) {
        FirestoreAiUsageStore store = new FirestoreAiUsageStore(firestore);
        assertThat(store.monthlyForUser("nobody", MONTH)).isEmpty();
        assertThat(store.monthlyGlobal("1999-01")).isEmpty();
    }

    @Test
    void concurrentIncrementsConvergeWithoutLostUpdates(Firestore firestore) throws Exception {
        FirestoreAiUsageStore store = new FirestoreAiUsageStore(firestore);

        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier startLine = new CyclicBarrier(concurrency);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                store.recordEvent(event("race", AiFeature.MEAL_PHOTO, 10, 5, 0, 0.01));
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        AiUsageSummary user = store.monthlyForUser("race", MONTH).orElseThrow();
        assertThat(user.totalCalls())
            .as("FieldValue.increment must not lose concurrent updates")
            .isEqualTo(concurrency);
        assertThat(user.totalInputTokens()).isEqualTo(10L * concurrency);
    }
}
