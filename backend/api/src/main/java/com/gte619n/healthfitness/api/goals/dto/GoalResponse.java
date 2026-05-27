package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalSource;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record GoalResponse(
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
    List<String> phaseOrder,
    GoalSource source
) {
    public static GoalResponse from(Goal g) {
        return new GoalResponse(
            g.goalId(),
            g.title(),
            g.description(),
            g.domain(),
            g.status(),
            g.startDate(),
            g.targetDate(),
            g.createdAt(),
            g.updatedAt(),
            g.completedAt(),
            g.phaseOrder() == null ? List.of() : g.phaseOrder(),
            g.source()
        );
    }
}
