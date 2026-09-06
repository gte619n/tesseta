package com.gte619n.healthfitness.core.progression;

import java.time.Instant;

/**
 * A shadow-mode prediction record (IMPL-PROG-01 §10, D5): for each completed
 * exercise, what each model ({@code double_progression} vs {@code kalman_v1})
 * predicted the user would achieve at the load actually prescribed, scored
 * against the actual. Runs permanently; roles reverse after promotion. This is
 * the only evidence that will ever exist (n=1) that the Kalman model beats the
 * simple rule.
 */
public record PredictionLog(
    String id,
    String userId,
    String exerciseId,
    String sessionId,
    String model,                  // "double_progression" | "kalman_v1"
    double prescribedLoad,
    double predictedReps,
    Double predictedRir,
    Integer actualReps,
    Double actualRir,
    Double absoluteError,          // |predictedReps − actualReps|, null until actual known
    Instant createdAt
) {}
