package com.gte619n.healthfitness.core.progression;

/**
 * Provenance of a set's reps-in-reserve (RIR) value — the progression engine's
 * only subjective input (IMPL-PROG-01 D1). Drives the observation-noise term
 * (§6.3): a REPORTED last-set RIR is trusted more than one inferred from hitting
 * the target, which in turn is trusted more than a bare target assumption.
 *
 * <ul>
 *   <li>{@code REPORTED} — the user tapped a RIR value (highest information).
 *   <li>{@code INFERRED_FAILURE} — prescribed reps not completed → rir≈0.
 *   <li>{@code INFERRED_TARGET} — prescribed reps completed, no report → rir≈target (noisy).
 *   <li>{@code VELOCITY} — derived from velocity hardware (reserved; unused in v1, D14).
 *   <li>{@code ABSENT} — no RIR available → the observation drives no e1RM update.
 * </ul>
 */
public enum RirSource {
    REPORTED,
    INFERRED_FAILURE,
    INFERRED_TARGET,
    VELOCITY,
    ABSENT
}
