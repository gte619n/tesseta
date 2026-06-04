package com.gte619n.healthfitness.core.workout;

import java.util.List;
import java.util.Optional;

// TODO(IMPL-12 follow-up): when a writer for {workouts.count, workouts.weeklyVolume} exists, publish MetricChangedEvent via MetricChangedPublisher.
public interface WorkoutRepository {
    Optional<Workout> findById(String userId, String workoutId);
    List<Workout> findByUser(String userId);
    void save(Workout workout);
}
