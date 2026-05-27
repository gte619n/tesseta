package com.gte619n.healthfitness.core.metric;

import java.time.Instant;
import java.time.LocalDate;

public record DailyMetric(
    String userId,
    LocalDate date,
    Integer steps,
    Integer restingHeartRate,
    Integer sleepMinutes,
    Integer hrvMs,
    Integer sleepScore,
    Instant createdAt,
    Instant updatedAt
) {}
