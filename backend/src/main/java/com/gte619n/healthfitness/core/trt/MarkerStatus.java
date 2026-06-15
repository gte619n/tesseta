package com.gte619n.healthfitness.core.trt;

/** Where a marker's latest value sits relative to its reference range (plus a WATCH band). */
public enum MarkerStatus {
    LOW,
    IN_RANGE,
    HIGH,
    WATCH,
    UNKNOWN
}
