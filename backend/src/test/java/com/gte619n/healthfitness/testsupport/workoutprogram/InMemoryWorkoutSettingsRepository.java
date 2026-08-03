package com.gte619n.healthfitness.testsupport.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettings;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettingsRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryWorkoutSettingsRepository implements WorkoutSettingsRepository {

    private final Map<String, WorkoutSettings> store = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkoutSettings> find(String userId) {
        return Optional.ofNullable(store.get(userId));
    }

    @Override
    public void save(WorkoutSettings settings) {
        store.put(settings.userId(), settings);
    }

    public void clear() {
        store.clear();
    }
}
