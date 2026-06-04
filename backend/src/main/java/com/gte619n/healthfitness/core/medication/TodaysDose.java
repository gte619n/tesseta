package com.gte619n.healthfitness.core.medication;

import java.time.Instant;

/**
 * A scheduled dose for today with its taken status.
 */
public record TodaysDose(
    String medicationId,
    String drugName,
    String imageUrl,
    TimeWindow window,
    double dose,
    String unit,
    boolean taken,
    Instant takenAt      // (nullable)
) {}
