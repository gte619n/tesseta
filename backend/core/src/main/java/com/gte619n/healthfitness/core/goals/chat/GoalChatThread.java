package com.gte619n.healthfitness.core.goals.chat;

import java.time.Instant;

public record GoalChatThread(
    String userId,
    String threadId,
    String title,
    Instant createdAt,
    Instant updatedAt
) {}
