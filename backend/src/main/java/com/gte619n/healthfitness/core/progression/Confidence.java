package com.gte619n.healthfitness.core.progression;

/**
 * Three-level user-facing confidence indicator derived from {@code sigma/e1rm}
 * (IMPL-PROG-01 D12, spec open-Q5): a raw sigma is useless to the user, a band
 * is enough. Thresholds in {@link ProgressionMath#confidenceOf}.
 */
public enum Confidence {
    HIGH, MEDIUM, LOW
}
