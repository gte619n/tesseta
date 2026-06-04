package com.gte619n.healthfitness.core.workoutprogram;

import java.util.List;
import java.util.Optional;

public interface WorkoutProgramRepository {
    Optional<WorkoutProgram> findById(String userId, String programId);
    List<WorkoutProgram> findByUser(String userId);
    void save(WorkoutProgram program);
    void delete(String userId, String programId);
}
