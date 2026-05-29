package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.chat.GoalChatThread;
import java.time.Instant;

public record ChatThreadResponse(
    String threadId,
    String title,
    Instant createdAt,
    Instant updatedAt
) {
    public static ChatThreadResponse from(GoalChatThread t) {
        return new ChatThreadResponse(t.threadId(), t.title(), t.createdAt(), t.updatedAt());
    }
}
