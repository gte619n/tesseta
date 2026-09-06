package com.gte619n.healthfitness.core.progression;

/**
 * The block loop's training mode, selected from the 14-day rolling energy
 * balance (IMPL-PROG-01 §8.1). Determines expected drift, success criterion and
 * volume ceilings the other loops operate under.
 */
public enum BlockMode {
    /** Surplus: positive drift, add load, high volume ceiling. */
    GAINING,
    /** Near-maintenance (±300 kcal/day): ~zero drift, add load, moderate ceiling. */
    RECOMP,
    /** Deficit 300–500: zero drift, hold load at lower RIR, flat ceiling. */
    MAINTENANCE,
    /** Deficit >500: slightly negative drift, hold load at lower RIR, reduced ceiling. */
    RECOVERY
}
