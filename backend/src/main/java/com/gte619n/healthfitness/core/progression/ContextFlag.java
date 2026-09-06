package com.gte619n.healthfitness.core.progression;

/**
 * Session-context modifiers that widen observation noise and can only reduce
 * prescribed load (never raise it). Plumbing exists from day one; in v1 no
 * producer populates these beyond the session-level feeling mapping (D14/D20) —
 * readiness auto-flags are deferred to a later phase.
 */
public enum ContextFlag {
    POOR_SLEEP,
    ILLNESS,
    TIME_CONSTRAINED,
    DEFICIT,
    /** Derived from a low end-of-workout feeling (1–2 of 5), the sole v1 producer (D20). */
    ROUGH_SESSION
}
