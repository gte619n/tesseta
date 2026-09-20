package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;
import java.util.List;

/**
 * A source of completed workout sessions <em>outside</em> the program tree
 * (IMPL-ADHOC-01 AD-06). Lets ad-hoc runs feed the shared read paths — weekly
 * stats/streaks ({@link WorkoutSessionCompletionService}) and per-exercise
 * history / e1RM ({@link ExercisePerformanceDigestService}) — without those
 * services depending on the ad-hoc module (they depend only on this interface;
 * the ad-hoc module supplies the implementation). Spring injects all beans as a
 * list; with none, the injected list is empty and behaviour is unchanged.
 */
public interface CompletedSessionSource {

    /** A performed session reduced to what the shared read paths need. */
    record PerformedSession(LocalDate date, WorkoutDay session, String sourceId) {}

    /**
     * Completed sessions for the user with a date in {@code [from, to]}
     * (inclusive). Implementations include work whose parent template/program has
     * since been archived — performed work is history regardless (ADR-0012).
     */
    List<PerformedSession> completedSessions(String userId, LocalDate from, LocalDate to);
}
