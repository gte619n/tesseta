package com.gte619n.healthfitness.core.goals.eval;

import java.time.Instant;

/**
 * Reads scalar values out of the rest of the app on behalf of the Goals
 * module. This is the only seam between Goals code and other modules'
 * Firestore collections — Step evaluation never touches another
 * module's repo directly.
 *
 * One implementation today: {@link FirestoreMetricResolver}. Tests
 * substitute a fake implementation.
 */
public interface MetricResolver {

    /** Current scalar value of {@code key} for {@code userId}. */
    MetricValue resolve(String userId, MetricKey key);

    /**
     * Has the condition held for the entire {@code windowDays} window?
     *
     * Used by SUSTAINED Steps. For metrics that store latest-only
     * values this is a heuristic — see the implementation comments.
     */
    boolean sustainedHolds(
        String userId,
        MetricKey key,
        com.gte619n.healthfitness.core.goals.Comparator cmp,
        double target,
        int windowDays
    );

    /** Tally for {@code key} since {@code from}. Used by COUNT Steps. */
    long countSince(String userId, MetricKey key, Instant from);
}
