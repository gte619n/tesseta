package com.gte619n.healthfitness.testsupport.nutrition;

import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryNutritionDailyLogRepository implements NutritionDailyLogRepository {

    // byUser -> date string -> log
    private final Map<String, Map<String, NutritionDailyLog>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<NutritionDailyLog> findByDate(String userId, LocalDate date) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(date.toString()));
    }

    @Override
    public List<NutritionDailyLog> findByDateRange(String userId, LocalDate from, LocalDate to) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
            .filter(l -> !l.date().isBefore(from) && !l.date().isAfter(to))
            .sorted(java.util.Comparator.comparing(NutritionDailyLog::date))
            .toList();
    }

    @Override
    public void save(NutritionDailyLog log) {
        Instant now = Instant.now();
        NutritionDailyLog existing = byUser
            .computeIfAbsent(log.userId(), k -> new ConcurrentHashMap<>())
            .get(log.date().toString());
        Instant createdAt = existing != null && existing.createdAt() != null
            ? existing.createdAt()
            : (log.createdAt() != null ? log.createdAt() : now);
        NutritionDailyLog stored = new NutritionDailyLog(
            log.userId(),
            log.date(),
            log.proteinGrams(),
            log.carbsGrams(),
            log.fatGrams(),
            log.fiberGrams(),
            log.sugarGrams(),
            log.caloriesKcal(),
            createdAt,
            now
        );
        byUser.get(log.userId()).put(log.date().toString(), stored);
    }
}
