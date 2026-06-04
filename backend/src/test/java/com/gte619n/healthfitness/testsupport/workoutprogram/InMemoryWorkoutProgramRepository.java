package com.gte619n.healthfitness.testsupport.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryWorkoutProgramRepository implements WorkoutProgramRepository {

    private final Map<String, WorkoutProgram> store = new ConcurrentHashMap<>();

    private String key(String userId, String programId) {
        return userId + "/" + programId;
    }

    @Override
    public Optional<WorkoutProgram> findById(String userId, String programId) {
        return Optional.ofNullable(store.get(key(userId, programId)));
    }

    @Override
    public List<WorkoutProgram> findByUser(String userId) {
        return store.values().stream().filter(p -> userId.equals(p.userId())).toList();
    }

    @Override
    public void save(WorkoutProgram program) {
        store.put(key(program.userId(), program.programId()), program);
    }

    @Override
    public void delete(String userId, String programId) {
        store.remove(key(userId, programId));
    }

    public void clear() {
        store.clear();
    }
}
