package com.gte619n.healthfitness.core.nutrition;

/**
 * Lifecycle of an async "Adjust with AI" pass on a logged meal entry. A null
 * {@link MealAdjustment} means no adjustment activity at all; once a pass starts
 * the entry carries a {@link MealAdjustment} whose status walks:
 *
 * <pre>
 *   ADJUSTING ──job ok──▶ PENDING_REVIEW ──commit──▶ (cleared to null)
 *       │
 *       └job fail─▶ REJECTED
 * </pre>
 *
 * <p>Unlike {@link LeftoverStatus} there is no persistent {@code APPLIED} state:
 * committing an adjustment rewrites the entry's live macros/ingredients directly
 * (via the finalize paths) and clears the adjustment back to null. {@code discard}
 * also clears it to null.
 */
public enum AdjustStatus {

    /** Correction submitted; the background AI re-analysis of the meal is running. */
    ADJUSTING,

    /** Re-analysis produced a proposal awaiting the user's apply/discard. */
    PENDING_REVIEW,

    /** Re-analysis failed (analyzer unavailable / produced no food); the user can retry. */
    REJECTED
}
