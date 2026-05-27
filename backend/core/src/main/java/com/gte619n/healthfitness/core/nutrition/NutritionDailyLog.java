package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.time.LocalDate;

public record NutritionDailyLog(
    String userId,
    LocalDate date,
    Double proteinGrams,
    Double carbsGrams,
    Double fatGrams,
    Instant createdAt,
    Instant updatedAt
) {}
