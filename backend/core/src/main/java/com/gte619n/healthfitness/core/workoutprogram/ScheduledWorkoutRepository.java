package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;
import java.util.List;

public interface ScheduledWorkoutRepository {
    List<ScheduledWorkout> findByProgram(String userId, String programId, LocalDate from, LocalDate to);
    void save(ScheduledWorkout scheduled);

    /**
     * Persist many sessions at once. The default falls back to per-doc
     * {@link #save}; Firestore-backed implementations commit a batched write.
     * All items are expected to share the same user + program.
     */
    default void saveAll(List<ScheduledWorkout> items) {
        for (ScheduledWorkout sw : items) {
            save(sw);
        }
    }

    /** Remove future PLANNED sessions for a program (used before re-materializing). */
    void deletePlannedFrom(String userId, String programId, LocalDate from);
}
