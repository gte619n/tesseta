package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PhaseResponse(
    String phaseId,
    String goalId,
    String title,
    String description,
    int orderIndex,
    PhaseStatus status,
    LocalDate targetStartDate,
    LocalDate targetEndDate,
    Instant completedAt,
    List<String> stepOrder,
    List<StepResponse> steps      // populated only for deep responses; null/empty otherwise
) {
    public static PhaseResponse from(Phase p, List<StepResponse> steps) {
        return new PhaseResponse(
            p.phaseId(),
            p.goalId(),
            p.title(),
            p.description(),
            p.orderIndex(),
            p.status(),
            p.targetStartDate(),
            p.targetEndDate(),
            p.completedAt(),
            p.stepOrder() == null ? List.of() : p.stepOrder(),
            steps == null ? List.of() : steps
        );
    }
}
