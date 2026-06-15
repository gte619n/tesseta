package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;

/**
 * A compact, per-exercise rollup of what the user has actually lifted (IMPL-18,
 * decisions S2/S5/R2/R3). Computed from every {@code COMPLETED}
 * {@link ScheduledWorkout}'s logged sets across all of the user's programs
 * including imported history (ADR-0008), it grounds the program designer's load
 * prescriptions in real performance.
 *
 * <p>All fields are nullable where "no data" is meaningful: an exercise the user
 * has never performed simply isn't in the digest map. {@code lowConfidence}
 * marks a digest whose estimated 1RM rests only on weight-only imported rows
 * (reps null) — a conservative floor that informs the model but must never
 * anchor a prescribed load (R2).
 */
public record ExerciseDigest(
    String exerciseId,
    LocalDate lastPerformed,        // null if never performed
    Integer weeksSinceLast,         // whole weeks since lastPerformed vs today; null if never
    Double bestRecentWeightLbs,     // heaviest working-set weight in the trailing window; null if none
    Integer bestRecentReps,         // reps at that heaviest set; null for weight-only imported rows
    Double estimated1Rm,            // Epley w*(1+reps/30) on the best set; weight-only -> the weight itself; null if none
    boolean lowConfidence,          // true when only weight-only (reps==null) rows informed e1RM
    Double typicalRpe,              // mean working-set RPE seen; null if none recorded
    Integer minReps, Integer maxReps,   // rep range observed across logged sets; null if none
    Integer trailing4wkSets,        // count of logged hard sets in the last 4 weeks
    Integer prior4wkSets            // count in the 4 weeks before that (for a simple volume trend)
) {}
