package com.gte619n.healthfitness.core.workoutprogram;

import java.util.List;

/**
 * One exercise as performed in a block. References the global Exercise catalog
 * by {@code exerciseId}. Timed exercises use {@code durationSeconds}; others
 * use reps.
 *
 * <p>{@code loggedSets} is null/empty for plan templates and populated only in
 * a completed session's snapshot, where it records the sets actually performed
 * (IMPL-15 history import).
 */
public record Prescription(
    String exerciseId,
    int orderIndex,
    Integer sets,
    Integer repsMin,
    Integer repsMax,
    Integer durationSeconds,
    Intensity intensity,
    Integer restSeconds,
    String tempo,
    String notes,
    DeloadModifier deloadModifier,
    List<LoggedSet> loggedSets
) {}
