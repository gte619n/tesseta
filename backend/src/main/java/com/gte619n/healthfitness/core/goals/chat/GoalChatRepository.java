package com.gte619n.healthfitness.core.goals.chat;

import java.util.List;
import java.util.Optional;

public interface GoalChatRepository {
    void createThread(GoalChatThread thread);
    Optional<GoalChatThread> findThread(String userId, String threadId);
    List<GoalChatThread> listThreads(String userId);
    void appendMessage(String userId, GoalChatMessage message);
    List<GoalChatMessage> listMessages(String userId, String threadId);

    /**
     * Delete a thread and all of its messages. No-op if the thread does
     * not exist; the caller is responsible for the not-found check it
     * wants to surface to the user.
     */
    void deleteThread(String userId, String threadId);
}
