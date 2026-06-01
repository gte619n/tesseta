package com.gte619n.healthfitness.core.workoutprogram;

/**
 * One exercise as performed in a block. References the global Exercise catalog
 * by {@code exerciseId}. Timed exercises use {@code durationSeconds}; others
 * use reps.
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
    DeloadModifier deloadModifier
) {}
