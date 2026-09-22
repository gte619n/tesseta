package com.gte619n.healthfitness.core.exercise;

/**
 * How a lift's LOGGED weight maps to TOTAL external load, for reporting
 * (IMPL-PROG-LOAD-01 §4.1). The lifter logs per hand for dumbbells and dual
 * cable trainers (a pair of 60s is entered as 60); the workout dashboard
 * expresses those as total load so e1RM/tonnage/PRs line up with barbell lifts.
 *
 * <ul>
 *   <li>{@link #TOTAL} — the logged number is already the total load (barbell,
 *       machine, single-stack cable, bodyweight, single-arm dumbbell). factor 1.
 *   <li>{@link #PER_HAND} — the logged number is per hand; total = ×2 (bilateral
 *       dumbbell, dual/functional cable trainer). factor 2.
 * </ul>
 *
 * <p>This is a REPORTING concept only: the progression engine keeps its belief
 * in the logged (per-hand) space and never applies the factor (decision IL-3).
 */
public enum LoadConvention {
    TOTAL(1),
    PER_HAND(2);

    private final int factor;

    LoadConvention(int factor) {
        this.factor = factor;
    }

    /** The multiplier from logged weight to total display load (1 or 2). */
    public int factor() {
        return factor;
    }
}
