package com.gte619n.healthfitness.core.workoutprogram;

import java.time.Instant;

/**
 * The user's workout-preferences document — currently just the weekly streak
 * target: how many completed workouts a calendar week must contain to count
 * toward the "consecutive weeks" streak shown on the Workouts landing. Kept as a
 * separate settings document (not fields on {@link WorkoutProgram}) because it is
 * a user preference that outlives any individual program, mirroring
 * {@code ReminderSettings}.
 *
 * <p>Stored at {@code users/{userId}/settings/workout}. The streak itself is still
 * derived client-side from the {@link ScheduledWorkout} calendar; the backend only
 * stores the threshold so it survives reinstall and syncs across devices.
 *
 * @param userId            owner
 * @param weeklyStreakTarget completed workouts per week required to keep the
 *                           streak alive (clamped to {@link #MIN_TARGET}..{@link #MAX_TARGET})
 * @param updatedAt         server timestamp of the last write (null until stored)
 */
public record WorkoutSettings(
    String userId,
    int weeklyStreakTarget,
    Instant updatedAt
) {

    /** Default weekly target when the user has never configured one. */
    public static final int DEFAULT_TARGET = 4;

    /** A week needs at least one workout to mean anything. */
    public static final int MIN_TARGET = 1;

    /** More than two-a-day, every day, is not a realistic threshold. */
    public static final int MAX_TARGET = 14;

    public static WorkoutSettings defaults(String userId) {
        return new WorkoutSettings(userId, DEFAULT_TARGET, null);
    }

    /** Clamp a requested target into the supported range. */
    public static int clampTarget(int target) {
        return Math.max(MIN_TARGET, Math.min(MAX_TARGET, target));
    }
}
