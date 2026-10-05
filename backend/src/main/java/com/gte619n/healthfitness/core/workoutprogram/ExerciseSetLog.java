package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;

/**
 * One logged set for a single exercise, flattened across sessions for the
 * {@code get_exercise_history} Gemini tool (IMPL-18, S2). Lets the model drill
 * into the raw history behind an {@link ExerciseDigest}: each entry is the
 * performed {@code date}, the load and reps actually lifted, the effort as
 * {@code rir} (reps-in-reserve) and/or legacy {@code rpe} if recorded, the
 * {@code rirSource} provenance, and the {@code programId} the set was logged
 * under (including imported history). Also backs the coaching screen's
 * per-exercise history popup.
 */
public record ExerciseSetLog(
    LocalDate date, Double weightLbs, Integer reps, Double rpe,
    Double rir, String rirSource, String programId
) {}
