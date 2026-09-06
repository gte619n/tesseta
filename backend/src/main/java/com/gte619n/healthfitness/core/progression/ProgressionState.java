package com.gte619n.healthfitness.core.progression;

import java.time.Instant;

/**
 * The engine's running belief about one (user, exercise) pair (IMPL-PROG-01 §3).
 * {@code e1rmLbs} is the ONLY authoritative number in the system — every
 * prescription is a function of it and the block parameters. {@code sigmaLbs}
 * (std-dev of the belief) gates prescription aggressiveness and the confidence
 * indicator.
 *
 * <p>Units are pounds (engine-internal, matching {@code weightLbs}). Single
 * state per exercise: no per-machine (D9) or per-side (D10) split in v1.
 *
 * @param kalmanEligibleAt when the 2-week warm-up completes and the Kalman path
 *        may prescribe live; before it, Kalman runs shadow-only (D5). Null until
 *        the first observation seeds it.
 */
public record ProgressionState(
    String userId,
    String exerciseId,
    double e1rmLbs,
    double sigmaLbs,
    Instant lastObservedAt,
    int observationCount,
    int consecutiveMissedSessions,
    Instant kalmanEligibleAt,
    long version
) {
    /** A cold state with no belief yet — callers seed e1rm/sigma before first use. */
    public static ProgressionState cold(String userId, String exerciseId) {
        return new ProgressionState(userId, exerciseId, 0.0, 0.0, null, 0, 0, null, 0);
    }

    /** Confidence band from the current relative uncertainty (D12). */
    public Confidence confidence() {
        return ProgressionMath.confidenceOf(e1rmLbs, sigmaLbs);
    }
}
