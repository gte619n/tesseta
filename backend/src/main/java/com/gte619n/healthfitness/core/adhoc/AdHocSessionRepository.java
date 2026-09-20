package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for ad-hoc run sessions at
 * {@code users/{userId}/adhocWorkouts/{adhocId}/sessions/{sessionId}} (AD-04).
 * Runs reuse the {@link ScheduledWorkout} record with {@code programId := adhocId}
 * and {@code scheduledId := sessionId}; sessions persist only at terminal state
 * (AD-05).
 */
public interface AdHocSessionRepository {
    Optional<ScheduledWorkout> findById(String userId, String adhocId, String sessionId);

    /** All runs of one template, newest date first. */
    List<ScheduledWorkout> findByWorkout(String userId, String adhocId);

    /**
     * Every completed ad-hoc run for the user in [from, to], across all
     * templates. Backs the history/stats/e1RM inclusion (D6) via a
     * collection-group query. Includes runs whose template has since been
     * archived (performed work is history regardless — ADR-0012).
     */
    List<ScheduledWorkout> findCompletedByUser(String userId, LocalDate from, LocalDate to);

    /** Number of persisted runs of one template (for the idempotent runCount). */
    int countByWorkout(String userId, String adhocId);

    void save(String adhocId, ScheduledWorkout session);
}
