package com.gte619n.healthfitness.core.goals;

import java.time.Instant;

public record Step(
    String goalId,
    String phaseId,
    String stepId,
    String title,
    int orderIndex,
    StepKind kind,
    boolean done,
    Instant doneAt,
    // manualOverride = true if the user hand-set `done` — suppresses auto-eval.
    // Written only by the user via PATCH; cleared by the "Reset to auto" affordance.
    boolean manualOverride,
    StepMetricBinding metric    // null for MANUAL Steps
) {}
