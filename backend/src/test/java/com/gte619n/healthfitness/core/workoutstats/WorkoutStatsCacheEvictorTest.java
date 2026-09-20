package com.gte619n.healthfitness.core.workoutstats;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gte619n.healthfitness.config.CacheConfig;
import com.gte619n.healthfitness.core.progression.SessionCompletedEvent;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * BT-12: completing a session evicts that user's cached workout-stats scan, so
 * the Overview reflects the new workout immediately rather than waiting out the
 * TTL. The {@code @Cacheable} proxy is a no-op in a plain unit test, so this
 * verifies the invalidation contract directly: a value stored under the user's
 * key is gone after the {@link SessionCompletedEvent} the listener handles.
 */
class WorkoutStatsCacheEvictorTest {

    private static final String USER = "u-evict";

    private ConcurrentMapCacheManager cacheManager;
    private WorkoutStatsCacheEvictor evictor;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager(CacheConfig.WORKOUT_STATS);
        evictor = new WorkoutStatsCacheEvictor(cacheManager);
    }

    @Test
    void sessionCompletionEvictsTheUsersCachedScan() {
        Cache cache = cacheManager.getCache(CacheConfig.WORKOUT_STATS);
        cache.put(USER, "stale-scan");
        assertNotNull(cache.get(USER), "precondition: cached value present");

        evictor.onSessionCompleted(new SessionCompletedEvent(USER, sampleSession()));

        assertNull(cache.get(USER), "user's cached scan should be evicted on completion");
    }

    @Test
    void evictingIsScopedToTheCompletingUser() {
        Cache cache = cacheManager.getCache(CacheConfig.WORKOUT_STATS);
        cache.put(USER, "mine");
        cache.put("other-user", "theirs");

        evictor.onSessionCompleted(new SessionCompletedEvent(USER, sampleSession()));

        assertNull(cache.get(USER));
        assertNotNull(cache.get("other-user"), "another user's cache must be untouched");
    }

    private static ScheduledWorkout sampleSession() {
        return new ScheduledWorkout(
            USER, "p1", "2026-06-15_d1", java.time.LocalDate.of(2026, 6, 15),
            "ph1", "d1", "Day", 1, false, "gym-1",
            com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus.COMPLETED,
            new com.gte619n.healthfitness.core.workoutprogram.WorkoutDay(
                "d1", "Day", com.gte619n.healthfitness.core.location.DayOfWeek.MON, "gym-1", 0, List.of()),
            null, null, null);
    }
}
