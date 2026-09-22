package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.LoadConvention;

/**
 * Per-(user, exercise) equipment reality (IMPL-PROG-01 D8): the smallest real
 * load step and any fixed offset, plus whether the exercise progresses at all.
 * Absent doc ⇒ values derived from the exercise's equipment/implement by
 * {@link LoadingProfileResolver}; a stored doc is the user's override.
 *
 * @param loadIncrementLbs smallest achievable step (barbell 5 lb, fixed DB 5 lb, etc.)
 * @param loadOffsetLbs    bar weight / machine baseline / bodyweight portion added
 *                         to the lifted load before Epley (bodyweight movements
 *                         source this from body-comp, D8/§4.4)
 * @param progressionEligible false for warm-ups, rehab, skill work (D21)
 * @param loadConventionOverride IMPL-PROG-LOAD-01 (IL-2): the user's explicit
 *        per-hand/total override for reporting. Null ⇒ derive from the exercise
 *        (laterality + equipment). Reporting-only; never used by the engine.
 */
public record ExerciseLoadingProfile(
    String userId,
    String exerciseId,
    double loadIncrementLbs,
    double loadOffsetLbs,
    boolean progressionEligible,
    LoadConvention loadConventionOverride
) {
    /** Back-compat convenience: a profile with no convention override. */
    public ExerciseLoadingProfile(
        String userId, String exerciseId, double loadIncrementLbs,
        double loadOffsetLbs, boolean progressionEligible) {
        this(userId, exerciseId, loadIncrementLbs, loadOffsetLbs, progressionEligible, null);
    }
}
