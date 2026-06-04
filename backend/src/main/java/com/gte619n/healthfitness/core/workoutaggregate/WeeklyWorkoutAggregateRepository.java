package com.gte619n.healthfitness.core.workoutaggregate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WeeklyWorkoutAggregateRepository {
    Optional<WeeklyWorkoutAggregate> findByWeekStart(String userId, LocalDate weekStart);
    List<WeeklyWorkoutAggregate> findByDateRange(String userId, LocalDate from, LocalDate to);
    void save(WeeklyWorkoutAggregate aggregate);
}
