package com.gte619n.healthfitness.core.workoutprogram;

/**
 * Estimates how long a {@link WorkoutDay} will take to perform (IMPL-ADHOC-01
 * D5/AD-08). Pure and unit-testable; used both to fit the AI generator's time
 * budget and to show "~N min" on library cards.
 *
 * <p>Model (per prescription):
 * <ul>
 *   <li>Rep set: {@code reps × secPerRep + SET_SETUP_SECONDS}, where
 *       {@code secPerRep} is the tempo total when parseable (e.g. "3-1-2" → 6s),
 *       else {@link #DEFAULT_SEC_PER_REP}.</li>
 *   <li>Timed set (hold/cardio): {@code durationSeconds + SET_SETUP_SECONDS}.</li>
 *   <li>Rest is added between sets: {@code (sets - 1) × restSeconds} (the last
 *       set's rest is recovery you don't spend in the workout).</li>
 *   <li>Plus {@link #EXERCISE_TRANSITION_SECONDS} per exercise (find the
 *       station, set up).</li>
 * </ul>
 * Missing values fall back to conservative defaults so an under-specified
 * prescription still yields a sane estimate.
 */
public final class WorkoutDurationEstimator {

    static final double DEFAULT_SEC_PER_REP = 3.5;
    static final int SET_SETUP_SECONDS = 4;
    static final int EXERCISE_TRANSITION_SECONDS = 20;
    static final int DEFAULT_REST_SECONDS = 60;
    static final int DEFAULT_SETS = 3;
    static final int DEFAULT_REPS = 10;

    private WorkoutDurationEstimator() {}

    /** Estimated total seconds to perform the day. Zero for a null/empty day. */
    public static int estimateSeconds(WorkoutDay day) {
        if (day == null || day.blocks() == null) {
            return 0;
        }
        double total = 0;
        for (Block block : day.blocks()) {
            if (block == null || block.prescriptions() == null) {
                continue;
            }
            for (Prescription rx : block.prescriptions()) {
                if (rx == null) {
                    continue;
                }
                total += estimatePrescriptionSeconds(rx);
            }
        }
        return (int) Math.round(total);
    }

    private static double estimatePrescriptionSeconds(Prescription rx) {
        int sets = rx.sets() != null && rx.sets() > 0 ? rx.sets() : DEFAULT_SETS;
        double perSet;
        if (rx.durationSeconds() != null && rx.durationSeconds() > 0) {
            // Timed hold / cardio interval.
            perSet = rx.durationSeconds() + SET_SETUP_SECONDS;
        } else {
            int reps = repsOf(rx);
            perSet = reps * secPerRep(rx.tempo()) + SET_SETUP_SECONDS;
        }
        int rest = rx.restSeconds() != null && rx.restSeconds() >= 0
            ? rx.restSeconds() : DEFAULT_REST_SECONDS;
        double work = sets * perSet;
        double restTotal = Math.max(0, sets - 1) * (double) rest;
        return work + restTotal + EXERCISE_TRANSITION_SECONDS;
    }

    /** Midpoint of the rep range, or a default when unspecified. */
    private static int repsOf(Prescription rx) {
        Integer min = rx.repsMin();
        Integer max = rx.repsMax();
        if (min != null && max != null && max >= min) {
            return (min + max) / 2;
        }
        if (max != null) {
            return max;
        }
        if (min != null) {
            return min;
        }
        return DEFAULT_REPS;
    }

    /**
     * Seconds per rep from a tempo string like "3-1-2-0" (eccentric-pause-
     * concentric-pause). Sums the numeric segments; falls back to
     * {@link #DEFAULT_SEC_PER_REP} when absent or unparseable. A "0" segment is
     * treated as an explosive ~1s phase minimum so tempo "X-0-X" stays realistic.
     */
    static double secPerRep(String tempo) {
        if (tempo == null || tempo.isBlank()) {
            return DEFAULT_SEC_PER_REP;
        }
        String[] parts = tempo.trim().split("[-/\\s]+");
        double sum = 0;
        int counted = 0;
        for (String part : parts) {
            try {
                double v = Double.parseDouble(part.replace("X", "1").replace("x", "1"));
                sum += Math.max(v, part.equals("0") ? 0.5 : 0);
                counted++;
            } catch (NumberFormatException ignore) {
                // non-numeric segment — skip
            }
        }
        if (counted == 0 || sum <= 0) {
            return DEFAULT_SEC_PER_REP;
        }
        return sum;
    }
}
