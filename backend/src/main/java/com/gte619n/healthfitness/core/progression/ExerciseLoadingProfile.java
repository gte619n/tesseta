package com.gte619n.healthfitness.core.progression;

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
 */
public record ExerciseLoadingProfile(
    String userId,
    String exerciseId,
    double loadIncrementLbs,
    double loadOffsetLbs,
    boolean progressionEligible
) {}
