package com.gte619n.healthfitness.api.workoutprogram;

/**
 * Body of the "continue this program" extend-in-place call
 * ({@code POST /api/me/workout-programs/{programId}/continue}). {@code scope}
 * is {@code "WEEK"} (one more week of the last phase) or {@code "CYCLE"} (repeat
 * the whole periodization); unknown/absent values default to {@code WEEK}.
 */
public record ContinueProgramRequest(
    String scope
) {}
