package com.gte619n.healthfitness.persistence.workout;

import com.gte619n.healthfitness.core.workout.Workout;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// Scaffold. Persists to users/{userId}/workouts/{workoutId}. Real reads
// and writes land alongside Google Health ingestion in a later IMPL.
@Repository
public class WorkoutRepository implements com.gte619n.healthfitness.core.workout.WorkoutRepository {

    @Override
    public Optional<Workout> findById(String userId, String workoutId) {
        throw new UnsupportedOperationException("workout reads land with a later IMPL");
    }

    @Override
    public List<Workout> findByUser(String userId) {
        throw new UnsupportedOperationException("workout reads land with a later IMPL");
    }

    @Override
    public void save(Workout workout) {
        throw new UnsupportedOperationException("workout writes land with a later IMPL");
    }
}
