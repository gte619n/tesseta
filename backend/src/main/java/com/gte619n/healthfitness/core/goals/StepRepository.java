package com.gte619n.healthfitness.core.goals;

import java.util.List;
import java.util.Optional;

public interface StepRepository {
    Optional<Step> findById(String userId, String goalId, String phaseId, String stepId);
    List<Step> findByPhase(String userId, String goalId, String phaseId);
    List<Step> findByGoal(String userId, String goalId);              // for metric reverse-lookup within a Goal
    List<Step> findByMetricKey(String userId, String metricKey);      // for event-driven re-eval
    List<Step> findAllSustained(String userId);                       // for daily Job
    void save(String userId, Step step);
    void delete(String userId, String goalId, String phaseId, String stepId);
}
