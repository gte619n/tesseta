package com.gte619n.healthfitness.core.workoutprogram.chat;

import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import java.time.Instant;

/**
 * A workout-program design conversation. The {@code schedule} and {@code goalId}
 * are the form selections the user makes BEFORE the chat begins (training days +
 * gym per day, optional linked goal); they are fixed for the thread and drive
 * the per-gym exercise allow-lists fed to the model.
 */
public record WorkoutProgramChatThread(
    String userId,
    String threadId,
    String title,
    ProgramSchedule schedule,
    String goalId,
    Instant createdAt,
    Instant updatedAt
) {}
