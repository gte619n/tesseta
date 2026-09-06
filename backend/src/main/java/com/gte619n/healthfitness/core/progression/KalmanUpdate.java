package com.gte619n.healthfitness.core.progression;

/**
 * The scalar Kalman filter on e1RM (IMPL-PROG-01 §6.2) — the estimator's core.
 * What survives from fitness-fatigue modelling is this STRUCTURE, without the
 * unidentifiable parameters (§11.3). Pure so the golden tests pin the update
 * (cold-start seed, missed-weeks re-convergence).
 */
public final class KalmanUpdate {

    private KalmanUpdate() {}

    /**
     * Process variance per day: how fast the belief decays toward uncertainty
     * with no observations. Expressed as a fraction of e1RM² per day; this is
     * what makes missed weeks self-heal without special-case detraining logic.
     * [Guessing] — tune against §10.
     */
    public static final double PROCESS_VARIANCE_FRACTION_PER_DAY = 0.0004; // ~2% sd growth/day at the belief scale

    /** Result of one update: the new belief and the gain used (for logging/tests). */
    public record Result(double e1rmLbs, double sigmaLbs, double gain) {}

    /**
     * One predict + correct step.
     *
     * @param priorE1rm current belief mean
     * @param priorSigma current belief sd
     * @param expectedDriftPerDay block-loop drift (§6.2); + surplus, 0 maint, − deficit
     * @param daysSinceLast days since the last observation (grows uncertainty)
     * @param observedE1rm the Epley implied max from this set
     * @param observationSd sd of that observation, from {@link ObservationNoiseModel}
     */
    public static Result step(
        double priorE1rm,
        double priorSigma,
        double expectedDriftPerDay,
        double daysSinceLast,
        double observedE1rm,
        double observationSd
    ) {
        double days = Math.max(0, daysSinceLast);
        // Predict.
        double predicted = priorE1rm + expectedDriftPerDay * days;
        double processVar = PROCESS_VARIANCE_FRACTION_PER_DAY * Math.max(priorE1rm, 1.0) * Math.max(priorE1rm, 1.0);
        double uncertainty = priorSigma * priorSigma + processVar * days;
        // Correct.
        double obsVar = observationSd * observationSd;
        double gain = uncertainty / (uncertainty + obsVar);
        double newE1rm = predicted + gain * (observedE1rm - predicted);
        double newSigma = Math.sqrt((1.0 - gain) * uncertainty);
        return new Result(newE1rm, newSigma, gain);
    }

    /**
     * Grow uncertainty for a layoff (no observation arriving) — used by the
     * prediction step when returning from missed sessions so the engine comes
     * back less confident and prescribes conservatively (§6.2).
     */
    public static double driftSigma(double priorE1rm, double priorSigma, double daysSinceLast) {
        double days = Math.max(0, daysSinceLast);
        double processVar = PROCESS_VARIANCE_FRACTION_PER_DAY * Math.max(priorE1rm, 1.0) * Math.max(priorE1rm, 1.0);
        return Math.sqrt(priorSigma * priorSigma + processVar * days);
    }
}
