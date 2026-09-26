package com.gte619n.healthfitness.core.workoutstats;

import com.gte619n.healthfitness.config.CacheConfig;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedEvent;
import com.gte619n.healthfitness.core.progression.SessionCompletedEvent;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Evicts a user's cached workout-stats scan the moment their workout history
 * changes, so the Overview reflects it without waiting out the cache TTL
 * (IMPL-WEB-WORKOUT-01 §5.6 / BT-12). Two triggers:
 *
 * <ul>
 *   <li>{@link SessionCompletedEvent} — published by
 *       {@code WorkoutSessionCompletionService} after the completion fan-out
 *       (COMPLETED only), same event the progression engine consumes.</li>
 *   <li>Workout-keyed {@link MetricChangedEvent}s — published on <em>every</em>
 *       completion outcome, including SKIPPED and the COMPLETED→PLANNED
 *       un-complete/reset that fires no SessionCompletedEvent. Covering that
 *       path here is what lets the cache TTL be long (CacheConfig) instead of
 *       the 60s it needed when un-completing relied on expiry alone.</li>
 * </ul>
 *
 * <p>Both fire for a plain completion — the double eviction is harmless.
 * Best-effort: an eviction failure must never break the write, so it is
 * swallowed.
 */
@Component
public class WorkoutStatsCacheEvictor {

    /** The metric keys the completion service publishes on every outcome. */
    private static final Set<String> WORKOUT_METRIC_KEYS = Set.of(
        MetricKey.WORKOUTS_COUNT.key(),
        MetricKey.WORKOUTS_WEEKLY_VOLUME.key());

    private final CacheManager cacheManager;

    public WorkoutStatsCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @EventListener
    public void onSessionCompleted(SessionCompletedEvent event) {
        evict(event.userId());
    }

    /** Any workout-history write (complete / skip / un-complete) invalidates the scan. */
    @EventListener
    public void onMetricChanged(MetricChangedEvent event) {
        if (event.metricKey() != null && WORKOUT_METRIC_KEYS.contains(event.metricKey())) {
            evict(event.userId());
        }
    }

    /** Evict one user's cached scan. Public so callers can force freshness. */
    public void evict(String userId) {
        try {
            Cache cache = cacheManager.getCache(CacheConfig.WORKOUT_STATS);
            if (cache != null && userId != null) {
                cache.evict(userId);
            }
        } catch (RuntimeException ignored) {
            // Cache eviction is advisory; a freshly completed session still
            // surfaces within the TTL if this fails.
        }
    }
}
