package com.gte619n.healthfitness.core.progression;

import java.util.List;

/**
 * Deterministic double progression (IMPL-PROG-01 §6.6) — the live baseline
 * during the 2-week warm-up and the permanent fallback. Pure and fully golden-
 * tested; this is what runs most of the time and must be first-class, not an
 * error path.
 *
 * <p>Rule: complete all sets at the top of the rep range → add one increment and
 * reset to the bottom; fail to reach the bottom of the range on any set on two
 * consecutive sessions → subtract one increment; otherwise hold load (the user
 * works reps up through the band).
 */
public final class DoubleProgression {

    private DoubleProgression() {}

    /**
     * @param lastLoad the working load used last session (lb)
     * @param repsAtLoad reps achieved on each working set at that load
     * @param band the target rep range from the block loop
     * @param incrementLbs the exercise's smallest step
     * @param priorSessionFailed whether the PRIOR session also missed the bottom
     *        of the range (drives the two-consecutive-failures deload)
     * @param sets the number of sets to prescribe
     */
    public static PrescribedLoad next(
        double lastLoad,
        List<Integer> repsAtLoad,
        RepBand band,
        double incrementLbs,
        boolean priorSessionFailed,
        int sets
    ) {
        boolean allHitTop = !repsAtLoad.isEmpty() && repsAtLoad.stream().allMatch(r -> r >= band.max());
        boolean anyBelowBottom = repsAtLoad.stream().anyMatch(r -> r < band.min());

        double newLoad;
        Direction dir;
        String why;
        if (allHitTop) {
            newLoad = lastLoad + incrementLbs;
            dir = Direction.UP;
            why = "hit " + band.max() + " on all sets → +" + fmt(incrementLbs) + " lb";
        } else if (anyBelowBottom && priorSessionFailed) {
            newLoad = Math.max(0, lastLoad - incrementLbs);
            dir = Direction.DOWN;
            why = "missed " + band.min() + " twice → −" + fmt(incrementLbs) + " lb";
        } else if (anyBelowBottom) {
            newLoad = lastLoad;
            dir = Direction.HOLD;
            why = "missed " + band.min() + " → holding to retry";
        } else {
            newLoad = lastLoad;
            dir = Direction.HOLD;
            why = "in range → add a rep toward " + band.max();
        }

        PrescriptionRationale rationale = new PrescriptionRationale(
            ProgressionPath.DOUBLE_PROGRESSION, dir,
            newLoad - lastLoad, null, null, Confidence.HIGH,
            List.of("last: " + fmt(lastLoad) + "×" + describeReps(repsAtLoad), why));
        return new PrescribedLoad(sets, band.min(), band.max(), newLoad, rationale);
    }

    private static String describeReps(List<Integer> reps) {
        if (reps.isEmpty()) return "?";
        return reps.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("?");
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
