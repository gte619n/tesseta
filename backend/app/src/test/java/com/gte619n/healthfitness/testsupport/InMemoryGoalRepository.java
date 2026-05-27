package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryGoalRepository implements GoalRepository {

    private final Map<String, Map<String, Goal>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<Goal> findById(String userId, String goalId) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(goalId));
    }

    @Override
    public List<Goal> findByUser(String userId, GoalStatus status) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
            .filter(g -> status == null || g.status() == status)
            .sorted(Comparator.comparing(
                (Goal g) -> g.createdAt() != null ? g.createdAt() : Instant.EPOCH).reversed())
            .toList();
    }

    @Override
    public void save(Goal goal) {
        Instant now = Instant.now();
        Goal existing = byUser.computeIfAbsent(goal.userId(), k -> new ConcurrentHashMap<>())
            .get(goal.goalId());
        Instant createdAt = existing != null && existing.createdAt() != null
            ? existing.createdAt()
            : (goal.createdAt() != null ? goal.createdAt() : now);
        Goal stored = new Goal(
            goal.userId(),
            goal.goalId(),
            goal.title(),
            goal.description(),
            goal.domain(),
            goal.status(),
            goal.startDate(),
            goal.targetDate(),
            createdAt,
            now,                      // updatedAt — mirror Firestore serverTimestamp behavior
            goal.completedAt(),
            goal.phaseOrder(),
            goal.source()
        );
        byUser.get(goal.userId()).put(goal.goalId(), stored);
    }

    @Override
    public void delete(String userId, String goalId) {
        Map<String, Goal> u = byUser.get(userId);
        if (u != null) u.remove(goalId);
    }
}
