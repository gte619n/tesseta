package com.gte619n.healthfitness.persistence.platform;

import com.gte619n.healthfitness.core.platform.PlatformRateLimitStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Per-instance in-memory fallback for the /v1 rate limiter (ADR-0020, D18), used
// when Firestore is disabled (tests / local). Wires only when
// app.persistence.firestore-enabled=false, so exactly one PlatformRateLimitStore
// bean exists (the Firestore one otherwise).
@Component
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "false")
public class InMemoryPlatformRateLimitStore implements PlatformRateLimitStore {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long incrementAndGet(String key, long windowStartEpochSeconds) {
        String composite = key + "_" + windowStartEpochSeconds;
        long value = counters.computeIfAbsent(composite, k -> new AtomicLong())
            .incrementAndGet();
        // Bound memory: drop stale windows once the map grows large.
        if (counters.size() > 50_000) {
            String suffix = "_" + windowStartEpochSeconds;
            counters.keySet().removeIf(k -> !k.endsWith(suffix));
        }
        return value;
    }
}
