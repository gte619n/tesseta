package com.gte619n.healthfitness.shared.presentation.nutrition

import com.gte619n.healthfitness.shared.data.AdjustStatus
import com.gte619n.healthfitness.shared.data.LeftoverStatus
import com.gte619n.healthfitness.shared.data.MealAdjustment
import com.gte619n.healthfitness.shared.data.Leftover

/**
 * IMPL-IOS-01 Phase 3 Wave C — the shared async op-rail state machine for the
 * review flows (Adjust-with-AI and Remove-Leftovers). Both server-held proposals
 * move through the same four states; the notification deep links (adjust-review,
 * leftover-review/retake) target [PENDING_REVIEW] / [PENDING_RETAKE].
 *
 * This is the single source of truth mapping the wire status enums
 * ([AdjustStatus] / [LeftoverStatus]) onto the UI-facing lifecycle, so iOS and
 * Android render the SAME affordance for the same server state.
 */
enum class PendingNutritionOp {
    /** Backend still analyzing (ADJUSTING / ANALYZING) — show a working note. */
    PENDING,

    /** Proposal ready: show the review-diff sheet with Apply/Discard. */
    PENDING_REVIEW,

    /** Unreadable / low-confidence (REJECTED) — offer a retake. This is the
     *  notification "retake" deep-link target. */
    PENDING_RETAKE,

    /** The user (or the notification action) declined the proposal. */
    REJECTED,

    /** Committed — macros are now the adjusted / consumed amount. */
    APPLIED,
}

/** Map a wire [AdjustStatus] to the shared op lifecycle. */
fun AdjustStatus?.toPendingOp(hasProposal: Boolean): PendingNutritionOp = when (this) {
    AdjustStatus.ADJUSTING -> PendingNutritionOp.PENDING
    AdjustStatus.PENDING_REVIEW -> if (hasProposal) PendingNutritionOp.PENDING_REVIEW else PendingNutritionOp.PENDING
    AdjustStatus.REJECTED -> PendingNutritionOp.PENDING_RETAKE
    null -> PendingNutritionOp.PENDING
}

/** Map a wire [LeftoverStatus] to the shared op lifecycle. */
fun LeftoverStatus?.toPendingOp(hasProposal: Boolean): PendingNutritionOp = when (this) {
    LeftoverStatus.ANALYZING -> PendingNutritionOp.PENDING
    LeftoverStatus.PENDING_REVIEW -> if (hasProposal) PendingNutritionOp.PENDING_REVIEW else PendingNutritionOp.PENDING
    LeftoverStatus.REJECTED -> PendingNutritionOp.PENDING_RETAKE
    LeftoverStatus.APPLIED -> PendingNutritionOp.APPLIED
    null -> PendingNutritionOp.PENDING
}

/** Convenience projections for an entry's attached adjust/leftover carriers. */
fun MealAdjustment?.pendingOp(): PendingNutritionOp = this?.status.toPendingOp(this?.proposal != null)
fun Leftover?.pendingOp(): PendingNutritionOp = this?.status.toPendingOp(this?.proposal != null)
