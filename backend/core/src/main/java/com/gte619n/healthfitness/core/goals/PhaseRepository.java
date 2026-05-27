package com.gte619n.healthfitness.core.goals;

import java.util.List;
import java.util.Optional;

public interface PhaseRepository {
    Optional<Phase> findById(String userId, String goalId, String phaseId);
    List<Phase> findByGoal(String userId, String goalId);
    void save(String userId, Phase phase);
    void delete(String userId, String goalId, String phaseId);
}
