package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStepRepository implements StepRepository {

    // user -> goal -> phase -> stepId -> Step
    private final Map<String, Map<String, Map<String, Map<String, Step>>>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<Step> findById(String userId, String goalId, String phaseId, String stepId) {
        return Optional.ofNullable(stepMap(userId, goalId, phaseId).get(stepId));
    }

    @Override
    public List<Step> findByPhase(String userId, String goalId, String phaseId) {
        return stepMap(userId, goalId, phaseId).values().stream()
            .sorted(Comparator.comparingInt(Step::orderIndex))
            .toList();
    }

    @Override
    public List<Step> findByGoal(String userId, String goalId) {
        Map<String, Map<String, Step>> phaseMap = phasesForGoal(userId, goalId);
        List<Step> result = new ArrayList<>();
        for (Map<String, Step> steps : phaseMap.values()) {
            result.addAll(steps.values());
        }
        result.sort(Comparator.comparingInt(Step::orderIndex));
        return result;
    }

    @Override
    public List<Step> findByMetricKey(String userId, String metricKey) {
        List<Step> result = new ArrayList<>();
        for (Map<String, Map<String, Step>> goalMap : byUser.getOrDefault(userId, Map.of()).values()) {
            for (Map<String, Step> stepMap : goalMap.values()) {
                for (Step s : stepMap.values()) {
                    if (s.metric() != null && metricKey.equals(s.metric().metricKey())) {
                        result.add(s);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public List<Step> findAllSustained(String userId) {
        List<Step> result = new ArrayList<>();
        for (Map<String, Map<String, Step>> goalMap : byUser.getOrDefault(userId, Map.of()).values()) {
            for (Map<String, Step> stepMap : goalMap.values()) {
                for (Step s : stepMap.values()) {
                    if (s.kind() == StepKind.SUSTAINED) {
                        result.add(s);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void save(String userId, Step step) {
        stepMap(userId, step.goalId(), step.phaseId()).put(step.stepId(), step);
    }

    @Override
    public void delete(String userId, String goalId, String phaseId, String stepId) {
        stepMap(userId, goalId, phaseId).remove(stepId);
    }

    private Map<String, Step> stepMap(String userId, String goalId, String phaseId) {
        return byUser
            .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(goalId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(phaseId, k -> new ConcurrentHashMap<>());
    }

    private Map<String, Map<String, Step>> phasesForGoal(String userId, String goalId) {
        return byUser
            .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(goalId, k -> new ConcurrentHashMap<>());
    }
}
