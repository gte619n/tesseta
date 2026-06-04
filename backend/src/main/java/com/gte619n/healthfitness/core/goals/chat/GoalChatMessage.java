package com.gte619n.healthfitness.core.goals.chat;

import java.time.Instant;

public record GoalChatMessage(
    String threadId,
    String messageId,
    ChatRole role,
    String content,
    // The validated GoalProposalDto serialized as JSON, present only on
    // ASSISTANT messages that carried a propose_goal_structure tool call.
    // Null otherwise.
    String proposalJson,
    Instant createdAt
) {}
