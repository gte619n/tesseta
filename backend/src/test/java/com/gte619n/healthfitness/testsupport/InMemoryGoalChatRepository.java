package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.goals.chat.GoalChatMessage;
import com.gte619n.healthfitness.core.goals.chat.GoalChatRepository;
import com.gte619n.healthfitness.core.goals.chat.GoalChatThread;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryGoalChatRepository implements GoalChatRepository {

    // user -> threadId -> thread
    private final Map<String, Map<String, GoalChatThread>> threads = new ConcurrentHashMap<>();
    // user -> threadId -> ordered messages
    private final Map<String, Map<String, List<GoalChatMessage>>> messages = new ConcurrentHashMap<>();

    @Override
    public void createThread(GoalChatThread thread) {
        Instant now = Instant.now();
        Instant createdAt = thread.createdAt() != null ? thread.createdAt() : now;
        GoalChatThread stored = new GoalChatThread(
            thread.userId(), thread.threadId(), thread.title(), createdAt, now);
        threads.computeIfAbsent(thread.userId(), k -> new ConcurrentHashMap<>())
            .put(thread.threadId(), stored);
    }

    @Override
    public Optional<GoalChatThread> findThread(String userId, String threadId) {
        return Optional.ofNullable(threads.getOrDefault(userId, Map.of()).get(threadId));
    }

    @Override
    public List<GoalChatThread> listThreads(String userId) {
        return threads.getOrDefault(userId, Map.of()).values().stream()
            .sorted(Comparator.comparing(
                (GoalChatThread t) -> t.updatedAt() != null ? t.updatedAt() : Instant.EPOCH).reversed())
            .toList();
    }

    @Override
    public void appendMessage(String userId, GoalChatMessage message) {
        Instant now = Instant.now();
        GoalChatMessage stored = new GoalChatMessage(
            message.threadId(),
            message.messageId(),
            message.role(),
            message.content(),
            message.proposalJson(),
            message.createdAt() != null ? message.createdAt() : now
        );
        messages.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(message.threadId(), k -> new CopyOnWriteArrayList<>())
            .add(stored);
        // Touch the thread's updatedAt.
        Map<String, GoalChatThread> userThreads = threads.get(userId);
        if (userThreads != null) {
            GoalChatThread t = userThreads.get(message.threadId());
            if (t != null) {
                userThreads.put(t.threadId(),
                    new GoalChatThread(t.userId(), t.threadId(), t.title(), t.createdAt(), now));
            }
        }
    }

    @Override
    public void deleteThread(String userId, String threadId) {
        Map<String, GoalChatThread> userThreads = threads.get(userId);
        if (userThreads != null) {
            userThreads.remove(threadId);
        }
        Map<String, List<GoalChatMessage>> userMessages = messages.get(userId);
        if (userMessages != null) {
            userMessages.remove(threadId);
        }
    }

    @Override
    public List<GoalChatMessage> listMessages(String userId, String threadId) {
        List<GoalChatMessage> list = messages.getOrDefault(userId, Map.of()).get(threadId);
        if (list == null) return List.of();
        List<GoalChatMessage> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparing(
            (GoalChatMessage m) -> m.createdAt() != null ? m.createdAt() : Instant.EPOCH));
        return copy;
    }
}
