package com.gte619n.healthfitness.testsupport.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatMessage;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatRepository;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatThread;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryWorkoutProgramChatRepository implements WorkoutProgramChatRepository {

    private final Map<String, WorkoutProgramChatThread> threads = new ConcurrentHashMap<>();
    private final Map<String, List<WorkoutProgramChatMessage>> messages = new ConcurrentHashMap<>();

    private String key(String userId, String threadId) {
        return userId + "/" + threadId;
    }

    @Override
    public void createThread(WorkoutProgramChatThread thread) {
        threads.put(key(thread.userId(), thread.threadId()), thread);
        messages.computeIfAbsent(key(thread.userId(), thread.threadId()), k -> new CopyOnWriteArrayList<>());
    }

    @Override
    public Optional<WorkoutProgramChatThread> findThread(String userId, String threadId) {
        return Optional.ofNullable(threads.get(key(userId, threadId)));
    }

    @Override
    public List<WorkoutProgramChatThread> listThreads(String userId) {
        return threads.values().stream()
            .filter(t -> userId.equals(t.userId()))
            .sorted(Comparator.comparing(WorkoutProgramChatThread::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @Override
    public void appendMessage(String userId, WorkoutProgramChatMessage message) {
        messages.computeIfAbsent(key(userId, message.threadId()), k -> new CopyOnWriteArrayList<>()).add(message);
    }

    @Override
    public List<WorkoutProgramChatMessage> listMessages(String userId, String threadId) {
        return new ArrayList<>(messages.getOrDefault(key(userId, threadId), List.of()));
    }

    @Override
    public void deleteThread(String userId, String threadId) {
        threads.remove(key(userId, threadId));
        messages.remove(key(userId, threadId));
    }
}
