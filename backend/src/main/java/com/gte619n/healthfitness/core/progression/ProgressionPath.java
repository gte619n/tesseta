package com.gte619n.healthfitness.core.progression;

/**
 * Which code path produced a prescription — surfaced to the user in the
 * rationale (IMPL-PROG-01 D24). Every prescribed number names its origin so the
 * user can see why it changed.
 */
public enum ProgressionPath {
    /** Belief-driven scalar-Kalman prescription (live after the 2-week warm-up, D5). */
    KALMAN,
    /** Deterministic double-progression (the baseline and permanent fallback, §6.6). */
    DOUBLE_PROGRESSION,
    /** Fallback: fewer than 6 observations for this exercise. */
    FALLBACK_COLD_START,
    /** Fallback: stale/thin data (no RIR ≥2 sessions, or <1 obs/10 days). */
    FALLBACK_STALE,
    /** Fallback: Kalman output failed the ±10% sanity band. */
    FALLBACK_SANITY,
    /** Fallback: session not server-materialized (ad-hoc / beyond window, D23). */
    FALLBACK_UNMATERIALIZED,
    /** Warm-up window: Kalman is shadow-only, deterministic prescribes (D5). */
    WARMUP
}
