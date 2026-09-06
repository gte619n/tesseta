package com.gte619n.healthfitness.core.progression;

/**
 * What counts as a successful block for a given mode (IMPL-PROG-01 §8.1).
 * Critically, {@code HOLD_LOAD_AT_LOWER_RIR} makes flat performance a success in
 * a deficit and SUPPRESSES the week loop's deload trigger on FLAT trends (D11) —
 * without it the engine would deload the user repeatedly through a cut.
 */
public enum SuccessCriterion {
    ADD_LOAD,
    HOLD_LOAD_AT_LOWER_RIR
}
