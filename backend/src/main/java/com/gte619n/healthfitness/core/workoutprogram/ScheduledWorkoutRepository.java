package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduledWorkoutRepository {
    List<ScheduledWorkout> findByProgram(String userId, String programId, LocalDate from, LocalDate to);
    void save(ScheduledWorkout scheduled);

    /**
     * Precise lookup of one session by id. The default scans
     * {@link #findByProgram}; Firestore-backed impls override with a direct
     * document read.
     */
    default Optional<ScheduledWorkout> findById(String userId, String programId, String scheduledId) {
        for (ScheduledWorkout sw : findByProgram(userId, programId, LocalDate.MIN, LocalDate.MAX)) {
            if (sw.scheduledId().equals(scheduledId)) return Optional.of(sw);
        }
        return Optional.empty();
    }

    /**
     * Count sessions in a program with the given status. The default scans
     * {@link #findByProgram}; Firestore-backed impls override with a count
     * aggregation that reads no documents.
     */
    default int countByStatus(String userId, String programId, ScheduledStatus status) {
        int n = 0;
        for (ScheduledWorkout sw : findByProgram(userId, programId, LocalDate.MIN, LocalDate.MAX)) {
            if (sw.status() == status) n++;
        }
        return n;
    }

    /**
     * The most recent session date for a status, if any. The default scans
     * {@link #findByProgram}; Firestore-backed impls override with an ordered
     * limit-1 read.
     */
    default Optional<LocalDate> latestDateByStatus(String userId, String programId, ScheduledStatus status) {
        LocalDate latest = null;
        for (ScheduledWorkout sw : findByProgram(userId, programId, LocalDate.MIN, LocalDate.MAX)) {
            if (sw.status() == status && sw.date() != null && (latest == null || sw.date().isAfter(latest))) {
                latest = sw.date();
            }
        }
        return Optional.ofNullable(latest);
    }

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

    /**
     * Overwrite just the {@code session} snapshot of existing sessions (e.g. to
     * re-block a day) without merging. The default delegates to {@link #saveAll};
     * the Firestore impl must <em>replace</em> the nested session field wholesale,
     * because {@code SetOptions.merge()} does not replace nested arrays (so a
     * merged write would leave the old blocks in place). Items must already exist.
     */
    default void saveSessions(List<ScheduledWorkout> items) {
        saveAll(items);
    }

    /** Remove future PLANNED sessions for a program (used before re-materializing). */
    void deletePlannedFrom(String userId, String programId, LocalDate from);
}
