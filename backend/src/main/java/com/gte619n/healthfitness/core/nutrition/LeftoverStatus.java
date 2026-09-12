package com.gte619n.healthfitness.core.nutrition;

/**
 * Lifecycle of a "Remove Leftovers" pass on a composite meal entry
 * (IMPL-LEFTOVER-01). A null {@link Leftover} means no leftover activity at all;
 * once a pass starts the entry carries a {@link Leftover} whose status walks:
 *
 * <pre>
 *   ANALYZING ──job ok──▶ PENDING_REVIEW ──apply──▶ APPLIED
 *       │                                    ▲          │
 *       └job reject─▶ REJECTED               └─(re-run)─┘
 * </pre>
 *
 * <p>A re-run of an already-{@code APPLIED} entry recomputes consumed from the
 * preserved as-served baseline (spec D6); {@code restore} clears the leftover
 * back to null (spec D15).
 */
public enum LeftoverStatus {

    /** Leftover photo uploaded; background comparison against the original is running. */
    ANALYZING,

    /** Analysis produced a valid estimate awaiting the user's confirm/discard. */
    PENDING_REVIEW,

    /** Analysis was unusable (low confidence / hard mismatch); the user should retake. */
    REJECTED,

    /** The consumed estimate has been committed onto the entry. */
    APPLIED
}
