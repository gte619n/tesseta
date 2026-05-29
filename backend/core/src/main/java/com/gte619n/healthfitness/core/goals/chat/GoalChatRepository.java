package com.gte619n.healthfitness.core.goals.chat;

import java.util.List;
import java.util.Optional;

public interface GoalChatRepository {
    void createThread(GoalChatThread thread);
    Optional<GoalChatThread> findThread(String userId, String threadId);
    List<GoalChatThread> listThreads(String userId);
    void appendMessage(String userId, GoalChatMessage message);
    List<GoalChatMessage> listMessages(String userId, String threadId);
}
