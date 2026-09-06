package com.gte619n.healthfitness.core.progression;

import java.util.Set;

/**
 * The multiplicative observation-noise model (IMPL-PROG-01 §6.3): all the
 * engine's judgment lives here. Returns the standard deviation (in lb) of one
 * e1RM observation; the Kalman update squares it into a variance.
 *
 * <p>Every constant is a STARTING VALUE ([Guessing], §14) to be tuned against
 * logged prediction error (§10) via the replay harness — never hand-guessed
 * live. Kept pure so the golden tests pin each factor.
 */
public final class ObservationNoiseModel {

    private ObservationNoiseModel() {}

    /** base_sd = 3% of current e1rm (§6.3). */
    public static final double BASE_SD_FRACTION = 0.03;

    /**
     * observation_sd = base · f_reps · f_source · f_set_position · f_context.
     *
     * @param e1rmLbs current belief (base scale)
     * @param totalCapableReps reps + rir (drives f_reps)
     * @param source RIR provenance (drives f_source; ABSENT → caller must skip the update)
     * @param reportedRir the reported RIR magnitude when source==REPORTED (accuracy degrades with distance from failure)
     * @param isLastWorkingSet last-set observations are the high-information ones
     * @param flags active context flags (each ×1.5, capped ×3)
     */
    public static double observationSd(
        double e1rmLbs,
        double totalCapableReps,
        RirSource source,
        Double reportedRir,
        boolean isLastWorkingSet,
        Set<ContextFlag> flags
    ) {
        double base = BASE_SD_FRACTION * Math.max(e1rmLbs, 1.0);
        return base
            * fReps(totalCapableReps)
            * fSource(source, reportedRir)
            * fSetPosition(isLastWorkingSet)
            * fContext(flags);
    }

    /** f_reps: repetition-max equations diverge above ~10 reps (§6.3). */
    public static double fReps(double totalReps) {
        if (totalReps <= 6) return 1.0;
        if (totalReps <= 10) return 1.3;
        if (totalReps <= 15) return 2.0;
        return 3.5;
    }

    /**
     * f_source, with the REPORTED value additionally scaled by RIR magnitude:
     * RIR accuracy degrades with distance from failure, so RIR 1 is reliable and
     * RIR 4 is close to a guess — multiplier {@code 1 + 0.15·rir} (§6.3).
     */
    public static double fSource(RirSource source, Double reportedRir) {
        return switch (source) {
            case VELOCITY -> 0.8;
            case INFERRED_FAILURE -> 1.0;
            case REPORTED -> 1.2 * (1.0 + 0.15 * (reportedRir == null ? 0 : Math.max(0, reportedRir)));
            case INFERRED_TARGET -> 2.0;
            case ABSENT -> Double.POSITIVE_INFINITY; // caller skips the update
        };
    }

    /** f_set_position: last working set is closest to failure, most accurate. */
    public static double fSetPosition(boolean isLastWorkingSet) {
        return isLastWorkingSet ? 1.0 : 1.8;
    }

    /** f_context: ×1.5 per active flag, capped at ×3.0. */
    public static double fContext(Set<ContextFlag> flags) {
        if (flags == null || flags.isEmpty()) return 1.0;
        double m = Math.pow(1.5, flags.size());
        return Math.min(m, 3.0);
    }
}
