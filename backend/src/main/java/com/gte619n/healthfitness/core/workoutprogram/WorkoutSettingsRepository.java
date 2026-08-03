package com.gte619n.healthfitness.core.workoutprogram;

import java.util.Optional;

/** Port for the singleton workout-preferences document. */
public interface WorkoutSettingsRepository {

    Optional<WorkoutSettings> find(String userId);

    void save(WorkoutSettings settings);
}
