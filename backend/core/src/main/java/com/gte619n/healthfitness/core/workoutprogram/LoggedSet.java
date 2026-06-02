package com.gte619n.healthfitness.core.workoutprogram;

/**
 * One set as actually performed in a completed session — the logged counterpart
 * to the planned {@link Prescription}. {@code weightLbs} is the load lifted
 * (0 for bodyweight); {@code reps} is the count, nullable when the source only
 * tracked weight (e.g. the imported history in IMPL-15).
 */
public record LoggedSet(
    Double weightLbs,
    Integer reps
) {}
