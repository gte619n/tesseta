package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.StepKind;

public record CreateStepRequest(
    String title,
    StepKind kind,
    StepMetricBindingDto metric    // null/required by kind — validated in controller
) {}
