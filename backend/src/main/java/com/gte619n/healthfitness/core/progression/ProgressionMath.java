package com.gte619n.healthfitness.core.progression;

/**
 * Pure, side-effect-free progression math (IMPL-PROG-01 §6). Kept separate from
 * the services so every formula is unit-testable in isolation (the golden
 * tests, §9.1). All loads are pounds.
 */
public final class ProgressionMath {

    private ProgressionMath() {}

    /** Confidence widening kicks in above this relative uncertainty (§6.4). */
    public static final double WIDEN_THRESHOLD = 0.06;
    /** Below this the belief is tight enough to call HIGH confidence. */
    public static final double HIGH_THRESHOLD = 0.03;

    /**
     * Epley implied 1RM from an observed set: {@code (load+offset)·(1+totalReps/30)}
     * where {@code totalReps = reps + rir} (§6.1). Adequate; its choice is not
     * load-bearing — divergence above ~10 reps is handled in the noise term.
     */
    public static double epleyE1rm(double loadPlusOffset, double totalCapableReps) {
        return loadPlusOffset * (1.0 + totalCapableReps / 30.0);
    }

    /**
     * Fraction of e1RM that a {@code (targetReps, targetRir)} set represents —
     * the inverse of Epley (§6.4): {@code 1/(1+(targetReps+targetRir)/30)}.
     */
    public static double targetPercent(double targetReps, double targetRir) {
        return 1.0 / (1.0 + (targetReps + targetRir) / 30.0);
    }

    /**
     * Raw prescribed load for a target, before increment flooring:
     * {@code e1rm·target_pct − offset}.
     */
    public static double rawLoad(double e1rmLbs, double targetReps, double targetRir, double loadOffsetLbs) {
        return e1rmLbs * targetPercent(targetReps, targetRir) - loadOffsetLbs;
    }

    /** Floor a load to the nearest achievable increment (never below zero). */
    public static double floorToIncrement(double rawLoad, double incrementLbs) {
        if (incrementLbs <= 0) return Math.max(0, rawLoad);
        double stepped = Math.floor(rawLoad / incrementLbs) * incrementLbs;
        return Math.max(0, stepped);
    }

    /** Three-level confidence band from relative uncertainty (D12). */
    public static Confidence confidenceOf(double e1rmLbs, double sigmaLbs) {
        if (e1rmLbs <= 0) return Confidence.LOW;
        double ratio = sigmaLbs / e1rmLbs;
        if (ratio <= HIGH_THRESHOLD) return Confidence.HIGH;
        if (ratio <= WIDEN_THRESHOLD) return Confidence.MEDIUM;
        return Confidence.LOW;
    }

    /** True when the belief is uncertain enough to widen the prescription (§6.4). */
    public static boolean shouldWiden(double e1rmLbs, double sigmaLbs) {
        return e1rmLbs > 0 && sigmaLbs / e1rmLbs > WIDEN_THRESHOLD;
    }

    /** Direction of a change, with a dead-band to avoid noise flip-flop. */
    public static Direction directionOf(double delta) {
        if (delta > 1e-6) return Direction.UP;
        if (delta < -1e-6) return Direction.DOWN;
        return Direction.HOLD;
    }
}
