package com.gte619n.healthfitness.core.nutrition;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NutritionDailyLogRepository {
    Optional<NutritionDailyLog> findByDate(String userId, LocalDate date);
    List<NutritionDailyLog> findByDateRange(String userId, LocalDate from, LocalDate to);
    void save(NutritionDailyLog log);
}
