package com.gte619n.healthfitness.core.workoutimport;

import java.util.List;

/**
 * Parsed shape of {@code future_workouts.json} — a name-only exercise catalog
 * plus a flat log of completed sessions tagged by phase. Plain records (no
 * Jackson) so {@code core} stays dependency-light; the JSON is deserialized in
 * a higher module (snake_case → these camelCase components) and handed to
 * {@link WorkoutHistoryImporter}.
 */
public record FutureWorkouts(
    List<CatalogExercise> exercises,
    List<Session> workouts
) {
    /** A catalog entry — id is a stable UUID, the canonical key for the history. */
    public record CatalogExercise(String id, String name) {}

    /** One completed training session. */
    public record Session(
        String completedTime,          // "yyyy-MM-dd HH:mm:ss"
        String workoutName,
        Integer durationSec,
        Boolean isFutureWorkout,
        List<SessionExercise> exercises,
        String phaseId,
        String phaseName
    ) {}

    /** An exercise as performed within a session. */
    public record SessionExercise(
        String exerciseId,
        String exerciseName,
        List<PerformedSet> sets
    ) {}

    /** One set as performed — weight only; reps are null in this export. */
    public record PerformedSet(Double weightLbs, Integer reps) {}
}
