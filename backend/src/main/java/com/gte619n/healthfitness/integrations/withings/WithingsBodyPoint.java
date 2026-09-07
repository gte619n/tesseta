package com.gte619n.healthfitness.integrations.withings;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import java.time.Instant;

// Normalized shape of a single Withings body measurement (one metric of one
// weigh-in). Provider-neutral so the webhook/backfill layer can build a
// BodyCompositionMeasurement (adding userId + the "WITHINGS" source platform)
// exactly as the Google Health path maps a GoogleHealthDataPoint.
//
// `value` is already unit-normalized to the metric's baked-in unit
// (WEIGHT_KG -> kilograms, BODY_FAT_PERCENT -> percent).
public record WithingsBodyPoint(
    String recordId,               // Withings measure-group id (stable per weigh-in)
    BodyCompositionMetric metric,
    double value,
    Instant sampleTime,
    String recordingMethod         // "AUTOMATIC" (device) or "MANUAL" (user-entered)
) {}
