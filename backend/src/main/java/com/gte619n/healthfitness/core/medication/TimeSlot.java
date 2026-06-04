package com.gte619n.healthfitness.core.medication;

/**
 * A time slot with its own dose amount.
 * Supports split dosing (e.g., 100mg morning + 100mg evening).
 */
public record TimeSlot(
    TimeWindow window,
    double dose
) {}
