package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import java.time.Instant;

public record StepResponse(
    String stepId,
    String phaseId,
    String goalId,
    String title,
    int orderIndex,
    StepKind kind,
    boolean done,
    Instant doneAt,
    boolean manualOverride,
    StepMetricBindingDto metric,
    // Transient flag: when an auto-done Step's metric has regressed across the target.
    // Computed by StepEvaluationService on read (Phase 3); always false in Phase 1.
    boolean metricRegressed
) {
    public static StepResponse from(Step s) {
        return new StepResponse(
            s.stepId(),
            s.phaseId(),
            s.goalId(),
            s.title(),
            s.orderIndex(),
            s.kind(),
            s.done(),
            s.doneAt(),
            s.manualOverride(),
            StepMetricBindingDto.from(s.metric()),
            false
        );
    }
}
