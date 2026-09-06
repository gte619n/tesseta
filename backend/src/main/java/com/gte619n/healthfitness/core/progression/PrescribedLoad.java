package com.gte619n.healthfitness.core.progression;

/**
 * A computed next-session prescription for one exercise: the numbers the engine
 * writes onto the {@code Prescription} plus the structured rationale
 * (IMPL-PROG-01 D24). Reps are a band; {@code targetWeightLbs} is floored to the
 * exercise's increment.
 */
public record PrescribedLoad(
    int sets,
    int repsMin,
    int repsMax,
    double targetWeightLbs,
    PrescriptionRationale rationale
) {}
