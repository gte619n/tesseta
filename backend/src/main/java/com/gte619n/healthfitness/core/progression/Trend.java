package com.gte619n.healthfitness.core.progression;

/** e1RM trend classification per movement pattern (IMPL-PROG-01 §7.1). */
public enum Trend {
    RISING, FLAT, FALLING,
    /** Not enough observations in the window to classify. */
    UNKNOWN
}
