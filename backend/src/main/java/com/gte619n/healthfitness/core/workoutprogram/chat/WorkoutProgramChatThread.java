package com.gte619n.healthfitness.core.workoutprogram.chat;

import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import java.time.Instant;

/**
 * A workout-program design conversation. The {@code schedule} and {@code goalId}
 * are the form selections the user makes BEFORE the chat begins (training days +
 * gym per day, optional linked goal); they are fixed for the thread and drive
 * the per-gym exercise allow-lists fed to the model.
 *
 * <p>{@code programId} (IMPL-18b) binds the thread to an already-active program
 * the user is editing in place; when set, the schedule/goal are seeded from that
 * program and committing updates it (forward re-materialization) rather than
 * creating a new draft. Null for the design-a-new-program flow.
 */
public record WorkoutProgramChatThread(
    String userId,
    String threadId,
    String title,
    ProgramSchedule schedule,
    String goalId,
    Instant createdAt,
    Instant updatedAt,
    String programId
) {
    /** Design-new convenience constructor (no bound program). */
    public WorkoutProgramChatThread(
        String userId, String threadId, String title, ProgramSchedule schedule,
        String goalId, Instant createdAt, Instant updatedAt
    ) {
        this(userId, threadId, title, schedule, goalId, createdAt, updatedAt, null);
    }
}
