package com.gte619n.healthfitness.core.workoutprogram.chat;

import com.gte619n.healthfitness.core.goals.chat.ChatRole;
import java.time.Instant;

public record WorkoutProgramChatMessage(
    String threadId,
    String messageId,
    ChatRole role,
    String content,
    // The proposed program (deep response) serialized as JSON, present only on
    // ASSISTANT messages that carried a propose_workout_program tool call. Null
    // otherwise.
    String proposalJson,
    Instant createdAt
) {}
