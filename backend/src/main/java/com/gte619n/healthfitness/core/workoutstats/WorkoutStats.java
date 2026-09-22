package com.gte619n.healthfitness.core.workoutstats;

import java.time.LocalDate;
import java.util.List;

/**
 * The read-model powering the web Overview dashboard (IMPL-WEB-WORKOUT-01):
 * consistency streak, weekly tonnage/session series, a day-level heatmap,
 * recent personal records, and the exercise lists the strength chart needs.
 * All of it is derived per-request from the user's COMPLETED
 * {@link com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout}s (the
 * expensive Firestore scan is cached; the derivation is a cheap fold).
 *
 * @param streak            consecutive-weeks streak + current-week progress
 * @param weeklySeries      exactly {@code weeks} entries, oldest→newest, the
 *                          current week last, zero-filled for weeks with no work
 * @param heatmap           only days with ≥1 completed session, last ~6 months
 * @param recentPrs         newest-first, capped at 5 (see {@link WorkoutStatsService})
 * @param chartDefaultLifts the lifts the strength chart defaults to (top by
 *                          observation count, ≤1 per movement pattern)
 * @param trackedExercises  every exercise with a plottable trend — logged on ≥2
 *                          sessions — newest-performed first (picker population)
 */
public record WorkoutStats(
    Streak streak,
    List<WeekPoint> weeklySeries,
    List<HeatmapDay> heatmap,
    List<PrPoint> recentPrs,
    List<LiftRef> chartDefaultLifts,
    List<TrackedExercise> trackedExercises
) {

    /**
     * @param current            consecutive ISO weeks (Monday start) meeting the
     *                           weekly target, including the current in-progress
     *                           week when it already meets the target
     * @param longest            the longest such run over all history
     * @param weeklyTarget       completed workouts/week required (the setting)
     * @param thisWeekCompleted  completed sessions so far in the current week
     * @param weekStart          the current week's Monday (caller's local zone)
     */
    public record Streak(int current, int longest, int weeklyTarget, int thisWeekCompleted, LocalDate weekStart) {}

    /** One week of the volume series. {@code tonnageLbs} is Σ(weight×reps). */
    public record WeekPoint(LocalDate weekStart, int sessions, double tonnageLbs) {}

    /**
     * One workout day for the consistency heatmap. {@code isDeload} is true when
     * the day's representative session was a scheduled deload (IMPL-DELOAD-01 D4).
     */
    public record HeatmapDay(LocalDate date, int sessionCount, SessionRef first, boolean isDeload) {}

    /** A stable pointer to one performed session (for deep-linking). */
    public record SessionRef(String programId, String scheduledId) {}

    /**
     * A personal-record session: a new best estimated 1RM for an exercise.
     *
     * <p>IMPL-PROG-LOAD-01 (D3/D8): {@code e1rmLbs}/{@code weightLbs} are the raw
     * logged (per-hand) numbers; {@code loadFactor} (1 or 2) and the pre-doubled
     * {@code e1rmTotalLbs}/{@code weightTotalLbs} express total load so dumbbell
     * lifts compare to barbell lifts. Web renders the totals.
     */
    public record PrPoint(
        String exerciseId, String exerciseName,
        double e1rmLbs, double weightLbs, Integer reps,
        LocalDate date, String programId, String scheduledId,
        int loadFactor, double e1rmTotalLbs, double weightTotalLbs) {}

    /** An exercise the strength chart can plot, with its display name. */
    public record LiftRef(String exerciseId, String exerciseName) {}

    /** An exercise with a plottable strength trend (≥2 sessions), for the chart's lift picker. */
    public record TrackedExercise(String exerciseId, String exerciseName, LocalDate lastPerformed) {}
}
