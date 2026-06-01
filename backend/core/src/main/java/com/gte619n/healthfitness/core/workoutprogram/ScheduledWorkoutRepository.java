package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;
import java.util.List;

public interface ScheduledWorkoutRepository {
    List<ScheduledWorkout> findByProgram(String userId, String programId, LocalDate from, LocalDate to);
    void save(ScheduledWorkout scheduled);
    /** Remove future PLANNED sessions for a program (used before re-materializing). */
    void deletePlannedFrom(String userId, String programId, LocalDate from);
}
