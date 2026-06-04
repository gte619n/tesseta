package com.gte619n.healthfitness.core.goals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Goal(
    String userId,
    String goalId,
    String title,
    String description,
    GoalDomain domain,
    GoalStatus status,
    LocalDate startDate,
    LocalDate targetDate,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    List<String> phaseOrder,    // ordered phaseIds, source of truth for sequence
    GoalSource source
) {}
