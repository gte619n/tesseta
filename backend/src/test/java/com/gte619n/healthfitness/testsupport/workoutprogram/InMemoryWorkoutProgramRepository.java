package com.gte619n.healthfitness.testsupport.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryWorkoutProgramRepository implements WorkoutProgramRepository {

    private final Map<String, WorkoutProgram> store = new ConcurrentHashMap<>();
    // Mirrors the Firestore soft-delete: a tombstoned doc stays in place but
    // is hidden from the default reads.
    private final Set<String> archived = ConcurrentHashMap.newKeySet();

    private String key(String userId, String programId) {
        return userId + "/" + programId;
    }

    @Override
    public Optional<WorkoutProgram> findById(String userId, String programId) {
        if (archived.contains(key(userId, programId))) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(key(userId, programId)));
    }

    @Override
    public List<WorkoutProgram> findByUser(String userId) {
        return store.values().stream()
            .filter(p -> userId.equals(p.userId()))
            .filter(p -> !archived.contains(key(p.userId(), p.programId())))
            .toList();
    }

    @Override
    public List<WorkoutProgram> findByUserIncludingArchived(String userId) {
        return store.values().stream().filter(p -> userId.equals(p.userId())).toList();
    }

    @Override
    public void save(WorkoutProgram program) {
        store.put(key(program.userId(), program.programId()), program);
        // The Firestore impl writes syncStatus=ACTIVE on every save, reviving
        // a previously tombstoned doc.
        archived.remove(key(program.userId(), program.programId()));
    }

    @Override
    public void delete(String userId, String programId) {
        archived.add(key(userId, programId));
    }

    public void clear() {
        store.clear();
        archived.clear();
    }
}
