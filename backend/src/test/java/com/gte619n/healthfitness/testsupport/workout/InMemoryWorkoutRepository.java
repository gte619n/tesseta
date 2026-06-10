package com.gte619n.healthfitness.testsupport.workout;

import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workout.WorkoutRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryWorkoutRepository implements WorkoutRepository {

    private final Map<String, Workout> store = new ConcurrentHashMap<>();

    private String key(String userId, String workoutId) {
        return userId + "/" + workoutId;
    }

    @Override
    public Optional<Workout> findById(String userId, String workoutId) {
        return Optional.ofNullable(store.get(key(userId, workoutId)));
    }

    @Override
    public List<Workout> findByUser(String userId) {
        return store.values().stream().filter(w -> userId.equals(w.userId())).toList();
    }

    @Override
    public void save(Workout workout) {
        store.put(key(workout.userId(), workout.workoutId()), workout);
    }

    @Override
    public void delete(String userId, String workoutId) {
        store.remove(key(userId, workoutId));
    }

    public void clear() {
        store.clear();
    }
}
