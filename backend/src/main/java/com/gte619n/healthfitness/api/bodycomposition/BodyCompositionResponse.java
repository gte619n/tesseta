package com.gte619n.healthfitness.api.bodycomposition;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import java.time.Instant;

public record BodyCompositionResponse(
    String recordId,
    BodyCompositionMetric metric,
    double value,
    Instant sampleTime,
    String sourcePlatform,
    String recordingMethod
) {
    public static BodyCompositionResponse from(BodyCompositionMeasurement m) {
        return new BodyCompositionResponse(
            m.recordId(),
            m.metric(),
            m.value(),
            m.sampleTime(),
            m.sourcePlatform(),
            m.recordingMethod()
        );
    }
}
