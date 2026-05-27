package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.StepKind;

public record UpdateStepRequest(
    String title,
    StepKind kind,
    Boolean done,              // manual check / un-check; sets manualOverride=true when present
    StepMetricBindingDto metric,
    Boolean resetToAuto        // when true, clears manualOverride and ignores `done`
) {}
