package com.gte619n.healthfitness.core.workoutstats;

import com.gte619n.healthfitness.config.CacheConfig;
import com.gte619n.healthfitness.core.progression.SessionCompletedEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Evicts a user's cached workout-stats scan the moment they complete a session,
 * so the Overview reflects the new workout without waiting out the cache TTL
 * (IMPL-WEB-WORKOUT-01 §5.6 / BT-12). Listens to the same
 * {@link SessionCompletedEvent} the progression engine does — published by
 * {@code WorkoutSessionCompletionService} after the completion fan-out — which
 * keeps the stats module decoupled from the completion service's constructor.
 *
 * <p>Un-completing a session (COMPLETED→PLANNED) fires no event, so that rarer
 * path relies on the short TTL instead (IL-3/IL-4). Best-effort: an eviction
 * failure must never break completion, so it is swallowed.
 */
@Component
public class WorkoutStatsCacheEvictor {

    private final CacheManager cacheManager;

    public WorkoutStatsCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @EventListener
    public void onSessionCompleted(SessionCompletedEvent event) {
        evict(event.userId());
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
            // surfaces within the short TTL if this fails.
        }
    }
}
