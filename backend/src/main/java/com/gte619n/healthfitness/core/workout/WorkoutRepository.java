package com.gte619n.healthfitness.core.workout;

import java.util.List;
import java.util.Optional;

// The writer is WorkoutSessionCompletionService (ADR-0012 / IMPL-16), which
// publishes MetricChangedEvent for {workouts.count, workouts.weeklyVolume}
// after its saves — mirroring how NutritionService publishes.
public interface WorkoutRepository {
    Optional<Workout> findById(String userId, String workoutId);
    List<Workout> findByUser(String userId);
    void save(Workout workout);

    /** Remove a workout (used when a logged session is un-completed to SKIPPED). */
    void delete(String userId, String workoutId);
}
