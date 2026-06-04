package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPhaseRepository implements PhaseRepository {

    // user -> goal -> phaseId -> Phase
    private final Map<String, Map<String, Map<String, Phase>>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<Phase> findById(String userId, String goalId, String phaseId) {
        return Optional.ofNullable(phaseMap(userId, goalId).get(phaseId));
    }

    @Override
    public List<Phase> findByGoal(String userId, String goalId) {
        return phaseMap(userId, goalId).values().stream()
            .sorted(Comparator.comparingInt(Phase::orderIndex))
            .toList();
    }

    @Override
    public void save(String userId, Phase phase) {
        phaseMap(userId, phase.goalId()).put(phase.phaseId(), phase);
    }

    @Override
    public void delete(String userId, String goalId, String phaseId) {
        phaseMap(userId, goalId).remove(phaseId);
    }

    private Map<String, Phase> phaseMap(String userId, String goalId) {
        return byUser
            .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(goalId, k -> new ConcurrentHashMap<>());
    }
}
