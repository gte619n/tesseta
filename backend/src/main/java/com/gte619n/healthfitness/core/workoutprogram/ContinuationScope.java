package com.gte619n.healthfitness.core.workoutprogram;

/**
 * How much to append when a user continues a finished program (extend-in-place).
 *
 * <ul>
 *   <li>{@link #WEEK} — one more week of the last phase's microcycle.</li>
 *   <li>{@link #CYCLE} — repeat the whole program's periodization once more
 *       (every phase's weeks, honoring each phase's deload week).</li>
 * </ul>
 *
 * Either way the appended sessions resume from the user's last logged
 * weights/reps (not the author's starting template), so progression picks up
 * exactly where it left off.
 */
public enum ContinuationScope {
    WEEK,
    CYCLE;

    /** Lenient parse for the request DTO; defaults to {@link #WEEK}. */
    public static ContinuationScope parse(String raw) {
        if (raw == null) {
            return WEEK;
        }
        try {
            return ContinuationScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return WEEK;
        }
    }
}
