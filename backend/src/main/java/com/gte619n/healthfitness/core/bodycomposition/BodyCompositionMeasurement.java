package com.gte619n.healthfitness.core.bodycomposition;

import java.time.Instant;

public record BodyCompositionMeasurement(
    String userId,
    String recordId,
    BodyCompositionMetric metric,
    double value,
    Instant sampleTime,
    String sourcePlatform,
    String recordingMethod,
    Instant createdAt,
    Instant updatedAt
) {}
