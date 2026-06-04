package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Test fake mirroring the Firestore DailyMetricRepository merge semantics:
// a save merges only the non-null metric fields into the day's record so a
// steps-only update doesn't clobber a stored sleep score.
public class InMemoryDailyMetricRepository implements DailyMetricRepository {

    private final Map<String, Map<LocalDate, DailyMetric>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<DailyMetric> findByDate(String userId, LocalDate date) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(date));
    }

    @Override
    public List<DailyMetric> findByDateRange(String userId, LocalDate from, LocalDate to) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
            .filter(m -> !m.date().isBefore(from) && !m.date().isAfter(to))
            .sorted(Comparator.comparing(DailyMetric::date))
            .toList();
    }

    @Override
    public void save(DailyMetric metric) {
        byUser.computeIfAbsent(metric.userId(), k -> new ConcurrentHashMap<>())
            .merge(metric.date(), metric, InMemoryDailyMetricRepository::mergeFields);
    }

    private static DailyMetric mergeFields(DailyMetric existing, DailyMetric incoming) {
        return new DailyMetric(
            existing.userId(),
            existing.date(),
            incoming.steps() != null ? incoming.steps() : existing.steps(),
            incoming.restingHeartRate() != null ? incoming.restingHeartRate() : existing.restingHeartRate(),
            incoming.sleepMinutes() != null ? incoming.sleepMinutes() : existing.sleepMinutes(),
            incoming.hrvMs() != null ? incoming.hrvMs() : existing.hrvMs(),
            incoming.sleepScore() != null ? incoming.sleepScore() : existing.sleepScore(),
            existing.createdAt(),
            incoming.updatedAt()
        );
    }

    // Convenience for assertions in tests.
    public List<DailyMetric> all(String userId) {
        return new ArrayList<>(byUser.getOrDefault(userId, Map.of()).values());
    }
}
