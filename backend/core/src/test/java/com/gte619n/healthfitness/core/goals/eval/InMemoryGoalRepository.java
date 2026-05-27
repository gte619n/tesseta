package com.gte619n.healthfitness.core.goals.eval;

import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory {@link GoalRepository} for the core unit tests in
 * this package. We can't reach the {@code app}-module InMemory repos
 * from here, so this is a thin local copy with just what the
 * StepEvaluationService tests need.
 */
final class InMemoryGoalRepository implements GoalRepository {

    private final Map<String, Map<String, Goal>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<Goal> findById(String userId, String goalId) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(goalId));
    }

    @Override
    public List<Goal> findByUser(String userId, GoalStatus status) {
        List<Goal> result = new ArrayList<>();
        for (Goal g : byUser.getOrDefault(userId, Map.of()).values()) {
            if (status == null || g.status() == status) result.add(g);
        }
        return result;
    }

    @Override
    public void save(Goal goal) {
        byUser.computeIfAbsent(goal.userId(), k -> new ConcurrentHashMap<>())
            .put(goal.goalId(), goal);
    }

    @Override
    public void delete(String userId, String goalId) {
        Map<String, Goal> u = byUser.get(userId);
        if (u != null) u.remove(goalId);
    }
}
