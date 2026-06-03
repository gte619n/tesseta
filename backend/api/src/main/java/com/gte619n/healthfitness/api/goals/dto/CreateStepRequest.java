package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.StepKind;

public record CreateStepRequest(
    String id,                     // optional client-minted UUID (IMPL-AND-20 D7); null ⇒ server-generated
    String title,
    StepKind kind,
    StepMetricBindingDto metric    // null/required by kind — validated in controller
) {}
