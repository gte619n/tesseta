package com.gte619n.healthfitness.core.goals.eval;

import com.gte619n.healthfitness.core.goals.Comparator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link MetricResolver} for unit tests. Tests stage scalar
 * values, sustained-window flags, and count tallies; no Spring context
 * needed.
 */
final class FakeMetricResolver implements MetricResolver {

    private final Map<String, MetricValue> values = new HashMap<>();
    private final Map<String, Boolean> sustained = new HashMap<>();
    private final Map<String, Long> counts = new HashMap<>();

    /** Stage a {@link MetricKey} value with a synthetic timestamp. */
    void put(String userId, MetricKey key, Double value, Instant asOf) {
        if (value == null) {
            values.put(keyFor(userId, key), MetricValue.unavailable());
        } else {
            values.put(keyFor(userId, key), MetricValue.of(value, asOf));
        }
    }

    /** Stage a SUSTAINED check answer for a (key) — coarse — used to drive flips. */
    void putSustained(String userId, MetricKey key, boolean holds) {
        sustained.put(keyFor(userId, key), holds);
    }

    /** Stage a count-since answer. */
    void putCount(String userId, MetricKey key, long count) {
        counts.put(keyFor(userId, key), count);
    }

    @Override
    public MetricValue resolve(String userId, MetricKey key) {
        return values.getOrDefault(keyFor(userId, key), MetricValue.unavailable());
    }

    @Override
    public boolean sustainedHolds(String userId, MetricKey key, Comparator cmp, double target, int windowDays) {
        return sustained.getOrDefault(keyFor(userId, key), Boolean.FALSE);
    }

    @Override
    public long countSince(String userId, MetricKey key, Instant from) {
        return counts.getOrDefault(keyFor(userId, key), 0L);
    }

    private static String keyFor(String userId, MetricKey key) {
        return userId + "|" + (key == null ? "<null>" : key.key());
    }
}
