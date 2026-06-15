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
    List<LoggedSet> loggedSets,
    Double targetWeightLbs,         // IMPL-18: concrete history-grounded load; null → fall back to intensity (RPE/%1RM)
    String loadBasis                // IMPL-18: short "why" for the prescribed load (e1RM, last done, ramp discount)
) {
    /**
     * Pre-IMPL-18 canonical signature. Delegates with the history-grounded load
     * fields null so existing callers (importer, splitter, completion service,
     * tests) compile unchanged (IMPL-18 D2).
     */
    public Prescription(
        String exerciseId, int orderIndex, Integer sets, Integer repsMin, Integer repsMax,
        Integer durationSeconds, Intensity intensity, Integer restSeconds, String tempo,
        String notes, DeloadModifier deloadModifier, List<LoggedSet> loggedSets
    ) {
        this(exerciseId, orderIndex, sets, repsMin, repsMax, durationSeconds, intensity,
            restSeconds, tempo, notes, deloadModifier, loggedSets, null, null);
    }
}
