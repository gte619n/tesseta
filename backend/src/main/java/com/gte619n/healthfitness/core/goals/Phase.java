package com.gte619n.healthfitness.core.goals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Phase(
    String goalId,
    String phaseId,
    String title,
    String description,
    int orderIndex,             // 0-based position in the sequence
    PhaseStatus status,
    LocalDate targetStartDate,
    LocalDate targetEndDate,
    Instant completedAt,
    List<String> stepOrder      // ordered stepIds
) {}
