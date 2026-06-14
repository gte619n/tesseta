package com.gte619n.healthfitness.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed cache layer (IMPL-20 Phase 2, finding #5/#6 + medium
 * impact). Short TTLs on low-churn reference reads and the per-user health
 * snapshot so hot paths (chat, goal reads) stop re-hitting Firestore on
 * every request.
 *
 * <p>Cache names referenced by {@link org.springframework.cache.annotation.Cacheable}
 * / {@link org.springframework.cache.annotation.CacheEvict}:
 *
 * <ul>
 *   <li>{@value #DRUG_BY_ID} — single drug catalog entries (very low churn).</li>
 *   <li>{@value #DRUG_CATALOG} — the {@code findAll} catalog list.</li>
 *   <li>{@value #USER_BY_ID} — user profile lookups.</li>
 *   <li>{@value #USER_HEALTH_SNAPSHOT} — the per-user chat health snapshot
 *       (rebuilds all ~24 metric reads); ~60s TTL so a chat burst reuses
 *       one build.</li>
 *   <li>{@value #EXERCISE_DIGEST} — the per-user IMPL-18 exercise-performance
 *       scan (every COMPLETED session's logged sets); ~60s TTL so a designer
 *       chat burst reuses one scan.</li>
 * </ul>
 *
 * <p>TTLs are deliberately short. Reference data (drugs) tolerates a few
 * minutes of staleness; the snapshot is capped at ~60s so a just-written
 * metric shows up on the next minute. Mutations evict explicitly via
 * {@code @CacheEvict} on the relevant repository writes.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String DRUG_BY_ID = "drugById";
    public static final String DRUG_CATALOG = "drugCatalog";
    public static final String USER_BY_ID = "userById";
    public static final String USER_HEALTH_SNAPSHOT = "userHealthSnapshot";
    public static final String EXERCISE_DIGEST = "exerciseDigest";

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
            // Drug catalog is near-static; 5 min keeps it fresh enough while
            // admin edits remain rare. Evicted on save/delete regardless.
            caffeineCache(DRUG_BY_ID, Duration.ofMinutes(5), 2_000),
            caffeineCache(DRUG_CATALOG, Duration.ofMinutes(5), 16),
            // User profiles change infrequently; 5 min, evicted on writes.
            caffeineCache(USER_BY_ID, Duration.ofMinutes(5), 5_000),
            // Health snapshot is the expensive one (~24 metric reads). Keep
            // it brief so live metric writes surface within a minute.
            caffeineCache(USER_HEALTH_SNAPSHOT, Duration.ofSeconds(60), 5_000),
            // Per-user exercise-performance scan (IMPL-18). Same brief TTL so a
            // freshly completed session surfaces within a minute.
            caffeineCache(EXERCISE_DIGEST, Duration.ofSeconds(60), 5_000)
        ));
        return manager;
    }

    private static CaffeineCache caffeineCache(String name, Duration ttl, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maxSize)
            .build());
    }
}
