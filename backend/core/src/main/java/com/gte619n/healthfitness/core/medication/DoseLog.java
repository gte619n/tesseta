package com.gte619n.healthfitness.core.medication;

import java.time.Instant;

/**
 * Individual dose log entry within an adherence log.
 */
public record DoseLog(
    TimeWindow window,
    Instant takenAt,
    double dose
) {}
