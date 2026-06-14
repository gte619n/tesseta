package com.gte619n.healthfitness.core.workoutprogram;

import java.util.List;
import java.util.Optional;

public interface WorkoutProgramRepository {
    Optional<WorkoutProgram> findById(String userId, String programId);
    List<WorkoutProgram> findByUser(String userId);

    /**
     * Like {@link #findByUser}, but also returns soft-deleted (tombstoned)
     * programs. The weekly aggregate recompute needs this: completed sessions
     * are history regardless of program state (ADR-0012), so tonnage and
     * session counts must keep counting work performed under a program the
     * user has since deleted.
     */
    List<WorkoutProgram> findByUserIncludingArchived(String userId);

    void save(WorkoutProgram program);
    void delete(String userId, String programId);
}
