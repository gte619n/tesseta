package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;

/**
 * The async "Adjust with AI" state carried on a {@link FoodEntry}. A null
 * adjustment on an entry means no adjustment activity.
 *
 * <p>The correction runs server-side in a background job: the entry flips to
 * {@link AdjustStatus#ADJUSTING} on submit, the job stores its result as a
 * {@link MealAdjustmentService.AdjustmentProposal} under
 * {@link AdjustStatus#PENDING_REVIEW}, and committing rewrites the entry's live
 * macros/ingredients then clears this back to null. The user's free-text
 * {@code instruction} and {@code saveAsMeal} choice are captured at submit and
 * carried through so the job (and a notification-driven commit) can read them
 * back without re-prompting.
 *
 * @param status      lifecycle stage (see {@link AdjustStatus})
 * @param instruction the user's free-text correction (kept for context/retry)
 * @param saveAsMeal  whether committing should also save the corrected meal
 * @param proposal    the pending proposal when {@code PENDING_REVIEW}, else null
 * @param analyzedAt  when the proposal was produced, else null
 */
public record MealAdjustment(
    AdjustStatus status,
    String instruction,
    boolean saveAsMeal,
    MealAdjustmentService.AdjustmentProposal proposal,
    Instant analyzedAt
) {}
