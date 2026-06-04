package com.gte619n.healthfitness.core.medication;

import java.time.LocalDate;

/**
 * Configuration for cycling protocols (e.g., 4 weeks on, 2 weeks off).
 */
public record CycleConfig(
    int onWeeks,
    int offWeeks,
    LocalDate startDate
) {}
