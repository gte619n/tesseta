package com.gte619n.healthfitness.core.bodycomposition;

// The unit is baked into the enum name so the `value` field on a
// BodyCompositionMeasurement is always a plain double — no unit-conversion
// arithmetic in callers.
public enum BodyCompositionMetric {
    WEIGHT_KG,
    BODY_FAT_PERCENT,
    LEAN_MASS_KG,
    BMI
}
