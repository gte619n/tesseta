package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class InMemoryExerciseRepository implements ExerciseRepository {

    private final Map<String, Exercise> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Exercise> findById(String exerciseId) {
        return Optional.ofNullable(store.get(exerciseId));
    }

    @Override
    public List<Exercise> findByIds(Collection<String> exerciseIds) {
        return exerciseIds.stream().map(store::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Exercise> findPublished(String search, MovementPattern pattern, BlockType block, String muscle) {
        String s = search == null ? null : search.toLowerCase();
        return store.values().stream()
            .filter(e -> e.status() == ExerciseStatus.PUBLISHED)
            .filter(e -> e.aliasOfExerciseId() == null)
            .filter(e -> pattern == null || e.movementPattern() == pattern)
            .filter(e -> block == null || (e.suitableBlockTypes() != null && e.suitableBlockTypes().contains(block)))
            .filter(e -> muscle == null || (e.primaryMuscles() != null
                && e.primaryMuscles().stream().anyMatch(m -> m.equalsIgnoreCase(muscle))))
            .filter(e -> s == null || (e.nameLower() != null && e.nameLower().contains(s)))
            .toList();
    }

    @Override
    public List<Exercise> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<Exercise> findByMediaStatus(ExerciseMediaStatus mediaStatus) {
        return store.values().stream()
            .filter(e -> e.mediaStatus() == mediaStatus)
            .filter(e -> e.aliasOfExerciseId() == null)
            .toList();
    }

    @Override
    public void save(Exercise exercise) {
        store.put(exercise.exerciseId(), exercise);
    }

    @Override
    public void delete(String exerciseId) {
        store.remove(exerciseId);
    }

    public void clear() {
        store.clear();
    }
}
