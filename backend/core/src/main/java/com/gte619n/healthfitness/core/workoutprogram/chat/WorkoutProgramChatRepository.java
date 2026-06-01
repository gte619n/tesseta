package com.gte619n.healthfitness.core.workoutprogram.chat;

import java.util.List;
import java.util.Optional;

public interface WorkoutProgramChatRepository {
    void createThread(WorkoutProgramChatThread thread);
    Optional<WorkoutProgramChatThread> findThread(String userId, String threadId);
    List<WorkoutProgramChatThread> listThreads(String userId);
    void appendMessage(String userId, WorkoutProgramChatMessage message);
    List<WorkoutProgramChatMessage> listMessages(String userId, String threadId);
    void deleteThread(String userId, String threadId);
}
