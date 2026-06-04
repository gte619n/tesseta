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
    // Transient flag: true when an auto-done Step's metric has
    // regressed across the target. Null for Steps where the flag is
    // meaningless: MANUAL Steps, manualOverride Steps, and undone
    // Steps. Computed on read by StepEvaluationService.
    Boolean metricRegressed
) {
    /** Build a response with metricRegressed unset (null). */
    public static StepResponse from(Step s) {
        return from(s, null);
    }

    /**
     * Build a response with an explicit regression flag. The
     * controller computes the flag for done, non-MANUAL, non-override
     * Steps and passes null otherwise.
     */
    public static StepResponse from(Step s, Boolean metricRegressed) {
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
            metricRegressed
        );
    }
}
