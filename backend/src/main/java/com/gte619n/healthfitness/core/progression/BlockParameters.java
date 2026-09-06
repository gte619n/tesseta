package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * The block loop's output — the parameters the session and week loops operate
 * under (IMPL-PROG-01 §8). Touches no loads directly; it sets the envelope.
 *
 * @param expectedDriftPerDay expected e1RM change per day, feeds the Kalman
 *        prediction step (§6.2). Positive in a surplus, ~0 maintenance, slightly
 *        negative in a deep deficit.
 */
public record BlockParameters(
    String userId,
    BlockMode mode,
    double expectedDriftPerDay,
    Map<MovementPattern, RepBand> repRangesByPattern,
    Map<Mechanic, Double> rirCapsByMechanic,
    Map<MovementPattern, Integer> weeklySetCeiling,
    SuccessCriterion successCriterion,
    boolean manualOverride,          // true when set via PUT (block loop won't overwrite)
    Instant computedAt,
    long version
) {
    /**
     * Sensible defaults for a brand-new user before the block loop has run:
     * RECOMP mode, zero drift, compound RIR cap 2 / isolation 1 (§8.2),
     * 8–12 rep bands, ceiling 20 sets/pattern.
     */
    public static BlockParameters defaults(String userId) {
        Map<MovementPattern, RepBand> reps = new EnumMap<>(MovementPattern.class);
        Map<MovementPattern, Integer> ceil = new EnumMap<>(MovementPattern.class);
        for (MovementPattern p : MovementPattern.values()) {
            reps.put(p, new RepBand(8, 12));
            ceil.put(p, 20);
        }
        Map<Mechanic, Double> caps = new EnumMap<>(Mechanic.class);
        caps.put(Mechanic.COMPOUND, 2.0);
        caps.put(Mechanic.ISOLATION, 1.0);
        return new BlockParameters(userId, BlockMode.RECOMP, 0.0, reps, caps, ceil,
            SuccessCriterion.ADD_LOAD, false, null, 0);
    }

    public RepBand repBand(MovementPattern pattern) {
        RepBand b = repRangesByPattern == null ? null : repRangesByPattern.get(pattern);
        return b != null ? b : new RepBand(8, 12);
    }

    public double rirCap(Mechanic mechanic) {
        Double c = rirCapsByMechanic == null ? null : rirCapsByMechanic.get(mechanic);
        return c != null ? c : (mechanic == Mechanic.ISOLATION ? 1.0 : 2.0);
    }

    public int ceiling(MovementPattern pattern) {
        Integer c = weeklySetCeiling == null ? null : weeklySetCeiling.get(pattern);
        return c != null ? c : 20;
    }
}
