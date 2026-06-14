package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;

/**
 * One logged set for a single exercise, flattened across sessions for the
 * {@code get_exercise_history} Gemini tool (IMPL-18, S2). Lets the model drill
 * into the raw history behind an {@link ExerciseDigest}: each entry is the
 * performed {@code date}, the load and reps actually lifted, the RPE if
 * recorded, and the {@code programId} the set was logged under (including
 * imported history).
 */
public record ExerciseSetLog(
    LocalDate date, Double weightLbs, Integer reps, Double rpe, String programId
) {}
