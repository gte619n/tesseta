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
        double floor = FLOOR_LBS;

        // Shoulder-isolation raises (lateral/front/rear-delt, reverse fly) are
        // logged with very small dumbbells — a few pounds a hand. The generic
        // 40 lb accessory ceiling below AND the 20 lb compound floor both read as
        // absurd for these (the "40 lb rear delt raise" bug), so give the raise
        // family its own light ceiling and floor before the accessory rules run.
        boolean shoulderRaise = containsAny(n, "lateral raise", "side raise",
            "front raise", "rear delt", "reverse fly", "rear fly", "delt fly");
        if (shoulderRaise) {
            base = Math.min(base, 15.0);
            floor = 10.0;
        } else if (containsAny(n, "cable", "pushdown", "pulldown", "pec deck",
            "kickback", "curl", "extension", "raise")) {
            // Other cable/machine isolation accessories start lighter than
            // free-weight compounds (pushdowns, curls, calf raises, etc.).
            base = Math.min(base, 40.0);
        }
        if (ex.mechanic() == Mechanic.ISOLATION) {
            base = Math.min(base, 40.0);
        }
        // Per-hand movements are LOGGED per hand, so the seed — the number the
        // athlete enters — must be a per-hand load, not the two-hand / barbell
        // figure baseForPattern returns. Without this a first-time dumbbell hinge
        // seeds a 95 lb "deadlift" that reads as absurd. Name-based, mirroring the
        // signals LoadConventionResolver.derive falls back to when the equipment
        // binding is sparse (this util has no equipment access).
        if (isPerHand(n)) {
            base = base / 2.0;
        }
        // Snap to a load that physically exists: dumbbells and plate steps come in
        // 5 lb increments, and the per-hand halving above can land off-rack (95/2 =
        // 47.5 lb — no such dumbbell). Floor rather than round: a seed errs light.
        base = ProgressionMath.floorToIncrement(base, SEED_INCREMENT_LBS);
        return Math.max(floor, base);
    }

    /** Every seeded load must land on the gym's coarsest real step (5 lb dumbbells/plates). */
    static final double SEED_INCREMENT_LBS = 5.0;

    /**
     * Whether the movement is logged per hand (a bilateral dumbbell lift or a
     * dual/functional cable trainer), detected by name — the same fallback signals
     * {@code LoadConventionResolver.derive} uses when the equipment binding is
     * sparse, so the seed lands in the same (per-hand) space the display expects.
     */
    private static boolean isPerHand(String nameLower) {
        return nameLower.contains("dumbbell") || nameLower.contains("db ")
            || nameLower.contains("dual cable") || nameLower.contains("dual-cable")
            || nameLower.contains("functional trainer") || nameLower.contains("cable crossover");
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
