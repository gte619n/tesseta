package com.gte619n.healthfitness.config;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.eval.MetricValue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Per-HTTP-request memo for resolved metric values, keyed by
 * {@code userId + MetricKey}.
 *
 * <p>One {@code GET /api/me/goals/{id}} can ask {@link
 * com.gte619n.healthfitness.core.goals.eval.MetricResolver#resolve} for
 * the same metric several times — once while auto-evaluating each bound
 * Step, then again while computing that Step's regression flag for the
 * response DTO. Each {@code resolve} is one or more Firestore reads, so
 * the repeats add up. This bean caches the first result for the life of
 * the request and serves the rest from memory.
 *
 * <p>Request-scoped, so the map is created fresh per request and discarded
 * when it ends — no cross-user leakage, no stale data across requests.
 * {@link CachingMetricResolver} only consults this bean when a request
 * scope is actually active (web requests); event-driven and batch metric
 * resolution runs with no request scope and bypasses it entirely.
 */
@Component
@RequestScope
public class RequestScopedMetricCache {

    private final Map<Key, MetricValue> values = new HashMap<>();

    /** Return the cached value for {@code (userId, key)} or compute + store it. */
    public MetricValue computeIfAbsent(String userId, MetricKey key, Supplier<MetricValue> loader) {
        return values.computeIfAbsent(new Key(userId, key), k -> loader.get());
    }

    private record Key(String userId, MetricKey key) {}
}
