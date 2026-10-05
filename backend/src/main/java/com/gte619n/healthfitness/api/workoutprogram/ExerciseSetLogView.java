package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.ExerciseSetLog;

/**
 * One logged set in the coaching screen's per-exercise history popup: the
 * performed {@code date} (ISO-8601 string), the load and reps lifted, the effort
 * ({@code rir} and/or legacy {@code rpe}), and the {@code rirSource} provenance.
 * The client groups these by date into sessions (newest first) so the athlete can
 * see "how I did last time" and sanity-check the coach's suggested load/reps.
 */
public record ExerciseSetLogView(
    String date, Double weightLbs, Integer reps, Double rir, String rirSource, Double rpe
) {
    public static ExerciseSetLogView from(ExerciseSetLog s) {
        return new ExerciseSetLogView(
            s.date() == null ? null : s.date().toString(),
            s.weightLbs(), s.reps(), s.rir(), s.rirSource(), s.rpe());
    }
}
