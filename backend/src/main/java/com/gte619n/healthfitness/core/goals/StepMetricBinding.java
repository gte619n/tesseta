package com.gte619n.healthfitness.core.goals;

import java.time.Instant;

public record StepMetricBinding(
    String metricKey,
    Comparator comparator,
    double targetValue,
    Integer windowDays,   // SUSTAINED only — null otherwise
    Instant countFrom     // COUNT only — null otherwise
) {}
