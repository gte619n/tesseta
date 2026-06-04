package com.gte619n.healthfitness.core.goals.eval;

import java.time.Instant;
import java.util.Optional;

/**
 * A snapshot of one metric's value at one point in time.
 *
 * {@code value} is empty when the backing module has no reading yet
 * (or the underlying repo is a stub). The evaluator treats an empty
 * value as "no information" — neither true nor false — so a Step bound
 * to an unavailable metric stays in its current state rather than
 * silently flipping based on a missing read.
 *
 * {@code asOf} is the timestamp of the underlying reading. For latest-
 * style metrics it's the reading's own timestamp. For computed metrics
 * (e.g. 7-day averages) it's the right edge of the window. Null when
 * the value is unavailable.
 */
public record MetricValue(Optional<Double> value, Instant asOf) {

    /** No reading available — evaluator should treat as "no change". */
    public static MetricValue unavailable() {
        return new MetricValue(Optional.empty(), null);
    }

    /** Convenience constructor for an available reading. */
    public static MetricValue of(double v, Instant t) {
        return new MetricValue(Optional.of(v), t);
    }

    public boolean isAvailable() {
        return value.isPresent();
    }
}
