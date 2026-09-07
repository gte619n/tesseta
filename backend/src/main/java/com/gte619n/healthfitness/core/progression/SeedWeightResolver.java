package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;

/**
 * A conservative <em>starting</em> load for a weighted exercise the engine has
 * never seen (IMPL-PROG-02 F6 / D7). Used only when there is no
 * {@code targetWeightLbs} and the movement is not bodyweight — so a first-time
 * lift (e.g. cable push-downs) announces a real, cautious number the athlete can
 * adjust, instead of the impossible "body weight".
 *
 * <p>v1 derives the seed from the movement pattern + mechanic + a light
 * name-based refinement for cables/machines (decision IMPL-D03: a pattern table
 * rather than a per-exercise catalog field, to avoid an Exercise-record
 * migration). Deliberately errs light: undershooting costs one easy set; a
 * per-exercise catalog override can be layered on later without touching callers.
 */
public final class SeedWeightResolver {

    private SeedWeightResolver() {}

    /** Absolute floor for any weighted seed. */
    public static final double FLOOR_LBS = 20.0;

    /**
     * Conservative starting load in lb, floored to the exercise increment implied
     * by its name. Returns 0 for bodyweight/timed/ineligible movements — callers
     * must gate on {@link BodyweightClassifier#isBodyweight} first.
     */
    public static double seedWeightLbs(Exercise ex) {
        if (ex == null) return FLOOR_LBS;
        String n = ex.nameLower() == null ? "" : ex.nameLower();
        double base = baseForPattern(ex);

        // Cables/machines/isolation accessories start lighter than free-weight compounds.
        if (containsAny(n, "cable", "pushdown", "pulldown", "pec deck", "kickback",
            "lateral raise", "reverse fly", "rear delt", "curl", "extension", "raise")) {
            base = Math.min(base, 40.0);
        }
        if (ex.mechanic() == Mechanic.ISOLATION) {
            base = Math.min(base, 40.0);
        }
        return Math.max(FLOOR_LBS, base);
    }

    private static double baseForPattern(Exercise ex) {
        MovementPattern p = ex.movementPattern();
        if (p == null) return 45.0;
        return switch (p) {
            case SQUAT, HINGE -> 95.0;                 // lower-body compounds (start ~ empty bar / light)
            case LUNGE, CARRY -> 45.0;
            case PUSH_HORIZONTAL, PULL_HORIZONTAL -> 45.0;
            case PUSH_VERTICAL, PULL_VERTICAL -> 45.0;
            case CORE -> 25.0;
            default -> 45.0;                            // CARDIO/MOBILITY/STRETCH/OTHER (rarely weighted)
        };
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
