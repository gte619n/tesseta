package com.gte619n.healthfitness.core.workoutaggregate;

import java.time.Instant;
import java.time.LocalDate;

public record WeeklyWorkoutAggregate(
    String userId,
    LocalDate weekStart,
    Double totalTonnage,
    Integer sessionCount,
    Instant createdAt,
    Instant updatedAt
) {}
