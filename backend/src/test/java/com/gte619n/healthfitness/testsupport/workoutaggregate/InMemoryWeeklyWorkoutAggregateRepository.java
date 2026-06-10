package com.gte619n.healthfitness.testsupport.workoutaggregate;

import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregate;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregateRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryWeeklyWorkoutAggregateRepository implements WeeklyWorkoutAggregateRepository {

    // byUser -> weekStart string -> aggregate
    private final Map<String, Map<String, WeeklyWorkoutAggregate>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<WeeklyWorkoutAggregate> findByWeekStart(String userId, LocalDate weekStart) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(weekStart.toString()));
    }

    @Override
    public List<WeeklyWorkoutAggregate> findByDateRange(String userId, LocalDate from, LocalDate to) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
            .filter(a -> !a.weekStart().isBefore(from) && !a.weekStart().isAfter(to))
            .sorted(java.util.Comparator.comparing(WeeklyWorkoutAggregate::weekStart))
            .toList();
    }

    @Override
    public void save(WeeklyWorkoutAggregate aggregate) {
        Instant now = Instant.now();
        WeeklyWorkoutAggregate existing = byUser
            .computeIfAbsent(aggregate.userId(), k -> new ConcurrentHashMap<>())
            .get(aggregate.weekStart().toString());
        Instant createdAt = existing != null && existing.createdAt() != null
            ? existing.createdAt()
            : (aggregate.createdAt() != null ? aggregate.createdAt() : now);
        WeeklyWorkoutAggregate stored = new WeeklyWorkoutAggregate(
            aggregate.userId(),
            aggregate.weekStart(),
            aggregate.totalTonnage(),
            aggregate.sessionCount(),
            createdAt,
            now
        );
        byUser.get(aggregate.userId()).put(aggregate.weekStart().toString(), stored);
    }

    public void clear() {
        byUser.clear();
    }
}
