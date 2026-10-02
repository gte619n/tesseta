package com.gte619n.healthfitness.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GeminiCallRecorderTest {

    /** Captures recorded events; stands in for the Firestore store. */
    private static final class FakeStore implements AiUsageStore {
        final List<AiUsageEvent> events = new ArrayList<>();
        boolean throwOnRecord = false;

        @Override
        public void recordEvent(AiUsageEvent event) {
            if (throwOnRecord) {
                throw new RuntimeException("firestore down");
            }
            events.add(event);
        }

        @Override
        public Optional<AiUsageSummary> monthlyForUser(String userId, String yearMonth) {
            return Optional.empty();
        }

        @Override
        public Optional<AiUsageSummary> monthlyGlobal(String yearMonth) {
            return Optional.empty();
        }

        @Override
        public List<AiUsageSummary> topSpenders(String yearMonth, int limit) {
            return List.of();
        }
    }

    private static CurrentUserProvider userProvider(String userId) {
        return () -> new CurrentUser(userId, userId + "@example.com", "Test", null);
    }

    private static CurrentUserProvider noUserProvider() {
        return () -> {
            throw new IllegalStateException("no authenticated user on this request");
        };
    }

    @Test
    void recordSuccessWritesEventAttributedToCurrentUserWithComputedCost() {
        FakeStore store = new FakeStore();
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), userProvider("u1"));

        recorder.recordSuccess(AiFeature.MEAL_PHOTO, "gemini-3.8-flash",
            1_000_000, 500_000, 0, false, Instant.now());

        assertThat(store.events).hasSize(1);
        AiUsageEvent e = store.events.get(0);
        assertThat(e.userId()).isEqualTo("u1");
        assertThat(e.feature()).isEqualTo(AiFeature.MEAL_PHOTO);
        assertThat(e.status()).isEqualTo(AiUsageEvent.Status.SUCCESS);
        assertThat(e.inputTokens()).isEqualTo(1_000_000);
        assertThat(e.outputTokens()).isEqualTo(500_000);
        // 0.30 + 1.25 = 1.55
        assertThat(e.estimatedCostUsd()).isCloseTo(1.55, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(e.eventId()).isNotBlank();
    }

    @Test
    void recordStreamingEventPreservesStreamingFlag() {
        FakeStore store = new FakeStore();
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), userProvider("u1"));

        recorder.recordSuccess(AiFeature.GOAL_CHAT, "gemini-3.1-pro-preview",
            2000, 800, 0, true, Instant.now());

        assertThat(store.events).hasSize(1);
        assertThat(store.events.get(0).streaming()).isTrue();
        assertThat(store.events.get(0).feature()).isEqualTo(AiFeature.GOAL_CHAT);
    }

    @Test
    void recordImageEventCountsImages() {
        FakeStore store = new FakeStore();
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), userProvider("u1"));

        recorder.recordSuccess(AiFeature.FOOD_IMAGE_GEN, "gemini-3.1-flash-image-preview",
            500, 0, 1, false, Instant.now());

        AiUsageEvent e = store.events.get(0);
        assertThat(e.images()).isEqualTo(1);
        assertThat(e.estimatedCostUsd()).isGreaterThan(0.0);
    }

    @Test
    void attributesToSystemBucketWhenNoCurrentUser() {
        FakeStore store = new FakeStore();
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), noUserProvider());

        recorder.recordSuccess(AiFeature.EXERCISE_MEDIA, "gemini-3.8-flash",
            100, 100, 0, false, Instant.now());

        assertThat(store.events).hasSize(1);
        assertThat(store.events.get(0).userId()).isEqualTo(AiUsageEvent.SYSTEM);
    }

    @Test
    void explicitUserIdOverridesCurrentUserForBackgroundJobs() {
        FakeStore store = new FakeStore();
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), noUserProvider());

        recorder.record(AiFeature.EXERCISE_ENRICH, "gemini-3.8-flash",
            100, 100, 0, AiUsageEvent.Status.SUCCESS, false, Instant.now(), "job-user");

        assertThat(store.events.get(0).userId()).isEqualTo("job-user");
    }

    @Test
    void recordErrorWritesErrorStatusWithZeroTokens() {
        FakeStore store = new FakeStore();
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), userProvider("u1"));

        recorder.recordError(AiFeature.MEAL_DESCRIBE, "gemini-3.8-flash", false, Instant.now());

        AiUsageEvent e = store.events.get(0);
        assertThat(e.status()).isEqualTo(AiUsageEvent.Status.ERROR);
        assertThat(e.inputTokens()).isZero();
        assertThat(e.outputTokens()).isZero();
    }

    @Test
    void storeFailureNeverPropagates() {
        FakeStore store = new FakeStore();
        store.throwOnRecord = true;
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), userProvider("u1"));

        // Must not throw — fire-and-forget (D6).
        recorder.recordSuccess(AiFeature.DRINK, "gemini-3.8-flash",
            10, 10, 0, false, Instant.now());

        assertThat(store.events).isEmpty();
    }

    @Test
    void nullFeatureFallsBackToUnknown() {
        FakeStore store = new FakeStore();
        GeminiCallRecorder recorder =
            new GeminiCallRecorder(store, new AiModelPricing(), userProvider("u1"));

        recorder.record(null, "gemini-3.8-flash", 1, 1, 0,
            AiUsageEvent.Status.SUCCESS, false, Instant.now(), null);

        assertThat(store.events.get(0).feature()).isEqualTo(AiFeature.UNKNOWN);
    }
}
