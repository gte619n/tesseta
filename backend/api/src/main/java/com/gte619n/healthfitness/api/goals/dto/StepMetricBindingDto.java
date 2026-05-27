package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import java.time.Instant;

public record StepMetricBindingDto(
    String metricKey,
    Comparator comparator,
    Double targetValue,
    Integer windowDays,
    Instant countFrom
) {
    public static StepMetricBindingDto from(StepMetricBinding m) {
        if (m == null) return null;
        return new StepMetricBindingDto(
            m.metricKey(),
            m.comparator(),
            m.targetValue(),
            m.windowDays(),
            m.countFrom()
        );
    }

    public StepMetricBinding toModel() {
        return new StepMetricBinding(
            metricKey,
            comparator,
            targetValue != null ? targetValue : 0.0,
            windowDays,
            countFrom
        );
    }
}
