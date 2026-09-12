package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.util.List;

/**
 * The "Remove Leftovers" state carried on a composite {@link FoodEntry}
 * (IMPL-LEFTOVER-01). A null leftover on an entry means no leftover activity.
 *
 * <p>Per spec D9, once a pass is applied the entry's <em>live</em>
 * {@code macros}/{@code ingredients} hold the <strong>consumed</strong> values so
 * every existing totals reader keeps working unchanged; this record preserves the
 * <strong>as-served</strong> baseline ({@link #servedMacros} +
 * {@link #servedIngredients}) so the diff can be shown, a re-run can recompute
 * from the baseline (D6), and "Restore full portion" (D15) can reset it.
 *
 * <p>The baseline is captured on the first analyze and never overwritten (D6/IL-3).
 *
 * @param status            lifecycle stage (see {@link LeftoverStatus})
 * @param servedMacros      the entry's total macros as served (preserved baseline)
 * @param servedIngredients the ingredient list as served (preserved baseline)
 * @param proposal          the pending estimate when {@code PENDING_REVIEW}, else null
 * @param analyzedAt        when the last apply committed, else null
 */
public record Leftover(
    LeftoverStatus status,
    Macros servedMacros,
    List<CompositeIngredient> servedIngredients,
    LeftoverProposal proposal,
    Instant analyzedAt
) {
    /** True once a consumed estimate has been committed at least once. */
    public boolean everApplied() {
        return analyzedAt != null;
    }
}
