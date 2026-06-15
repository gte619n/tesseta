package com.gte619n.healthfitness.core.trt;

import java.time.LocalDate;

/**
 * One historical data point for a single TRT marker, for the
 * {@code get_lab_history} designer tool.
 *
 * <p>Component names are part of the client wire contract — do not rename.
 */
public record TrtMarkerHistoryPoint(
    LocalDate date,
    Double value,
    String unit,
    Double refLow,
    Double refHigh
) {}
