package com.gte619n.healthfitness.core.progression;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a belief ({@link ProgressionState}) plus block parameters into a
 * prescription (IMPL-PROG-01 §6.4) — the Kalman/live path. Applies, in order:
 * jump cap → confidence widening → increment floor → rep progression. Pure and
 * golden-tested.
 *
 * <p>Load is computed toward the MIDPOINT of the rep band at the target RIR
 * (decision P1 in the log): heavy enough to drive progress, light enough to
 * complete the range and generate a clean observation.
 */
public final class PrescriptionCalculator {

    private PrescriptionCalculator() {}

    /**
     * Legacy flat jump cap (retired IMPL-PROG-LOAD-01 IL-7 — the +5% cap is
     * replaced by the increment-based speculative cap below; kept only so any
     * external reference still resolves).
     */
    public static final double JUMP_CAP = 1.05;
    /** Confidence widening drops the load this much when uncertainty is high. */
    public static final double WIDEN_LOAD_DROP = 0.025;

    /**
     * @param state belief for this exercise
     * @param band block-loop rep range
     * @param targetRir block-loop RIR cap for this exercise's mechanic
     * @param loadOffsetLbs bodyweight/machine offset (§4.4)
     * @param incrementLbs smallest real step (from the gym, D7)
     * @param lastPrescribedLoad the load prescribed last time; ≤0 if none
     * @param lastPerformedLoad the load actually used last time (demonstrated); ≤0 if none
     * @param outperformed the lifter beat the prescription with reps in reserve
     *        (IMPL-PROG-LOAD-01 D13/D14) — trust the demonstrated load
     * @param sets number of sets to prescribe
     */
    public static PrescribedLoad calculate(
        ProgressionState state,
        RepBand band,
        double targetRir,
        double loadOffsetLbs,
        double incrementLbs,
        double lastPrescribedLoad,
        double lastPerformedLoad,
        boolean outperformed,
        int sets
    ) {
        List<String> inputs = new ArrayList<>();
        inputs.add("e1RM ~" + Math.round(state.e1rmLbs()) + " lb");

        int targetReps = band.mid();
        RepBand outBand = band;

        double rawLoad = ProgressionMath.rawLoad(state.e1rmLbs(), targetReps, targetRir, loadOffsetLbs);

        // 1) Speculative one-increment cap (IL-7): don't jump more than one real
        // increment past the higher of what was prescribed or actually performed.
        // A genuinely heavier demonstrated load raises this ceiling (via
        // lastPerformedLoad), so proven capacity is never suppressed (D13).
        double capBasis = Math.max(lastPrescribedLoad, lastPerformedLoad);
        if (capBasis > 0 && rawLoad > capBasis + incrementLbs) {
            rawLoad = capBasis + incrementLbs;
            inputs.add("capped to +1 increment");
        }

        // 2) Confidence widening: uncertain belief → easier, wider set.
        if (ProgressionMath.shouldWiden(state.e1rmLbs(), state.sigmaLbs())) {
            outBand = new RepBand(Math.max(1, band.min() - 1), band.max() + 1);
            rawLoad *= (1.0 - WIDEN_LOAD_DROP);
            inputs.add("low confidence → wider range, lighter");
        }

        // 3) Demonstrated-override floor (D13/D14): if the lifter beat the target
        // with reps in reserve, match what they actually lifted (no add on top).
        // Applied last so confidence widening can't pull the target under a load
        // the lifter already proved.
        if (outperformed && lastPerformedLoad > 0) {
            double floor = ProgressionMath.floorToIncrement(lastPerformedLoad, incrementLbs);
            if (rawLoad < floor) {
                rawLoad = floor;
                inputs.add("matched demonstrated load");
            }
        }

        double prescribed = ProgressionMath.floorToIncrement(rawLoad, incrementLbs);

        // 3) Increment floor → rep progression: if load can't rise by a full
        // increment over what was last performed, hold load and advance reps
        // instead (this is where double progression falls out of the model).
        Direction dir;
        Double deltaLbs;
        Integer deltaReps = null;
        double baseline = lastPerformedLoad > 0 ? lastPerformedLoad : prescribed;
        if (lastPerformedLoad > 0 && prescribed <= lastPerformedLoad + 1e-6
            && prescribed >= lastPerformedLoad - 1e-6) {
            prescribed = lastPerformedLoad;
            dir = Direction.HOLD;
            deltaLbs = 0.0;
            deltaReps = 1;
            inputs.add("increment floor → add a rep");
        } else {
            deltaLbs = prescribed - baseline;
            dir = ProgressionMath.directionOf(deltaLbs);
        }

        Confidence confidence = ProgressionMath.confidenceOf(state.e1rmLbs(), state.sigmaLbs());
        PrescriptionRationale rationale = new PrescriptionRationale(
            ProgressionPath.KALMAN, dir, deltaLbs, deltaReps, null, confidence, inputs);
        return new PrescribedLoad(sets, outBand.min(), outBand.max(), prescribed, rationale);
    }
}
