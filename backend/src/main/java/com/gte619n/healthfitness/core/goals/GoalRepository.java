package com.gte619n.healthfitness.core.goals;

import java.util.List;
import java.util.Optional;

public interface GoalRepository {
    Optional<Goal> findById(String userId, String goalId);
    List<Goal> findByUser(String userId, GoalStatus status);   // null status = all
    void save(Goal goal);
    void delete(String userId, String goalId);                 // soft via status=ARCHIVED in GoalService
}
