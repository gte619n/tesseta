package com.gte619n.healthfitness.core.trt;

import java.time.LocalDate;

/**
 * A single TRT monitoring-panel marker resolved from the user's bloodwork:
 * its latest value, units, reference range, when it was sampled, the trend
 * across the two most recent reports, and its status vs. range.
 *
 * <p>Component names are part of the client wire contract — do not rename.
 */
public record TrtMarker(
    String name,          // canonical key, e.g. "totalTestosterone"
    String label,         // human-readable, e.g. "Total Testosterone"
    Double value,
    String unit,
    Double refLow,
    Double refHigh,
    LocalDate sampleDate,
    Trend trend,
    MarkerStatus status
) {}
