package com.gte619n.healthfitness.core.workoutprogram;

import java.time.Instant;

/**
 * The user's standing workout preferences, outliving any individual program:
 * <ul>
 *   <li>{@code weeklyStreakTarget} — how many completed workouts a calendar week
 *       must contain to count toward the "consecutive weeks" streak shown on the
 *       Workouts landing.</li>
 *   <li>{@code preferences} — free-text standing instructions the user wants the
 *       program designer to honor on every build (e.g. "no bent-over rows or
 *       anything that stresses the lower back"). Injected into the designer's
 *       prompt so it applies in perpetuity, not just for one conversation.</li>
 * </ul>
 * Kept as a separate settings document (not fields on {@link WorkoutProgram})
 * because these are user preferences that outlive any individual program,
 * mirroring {@code ReminderSettings}.
 *
 * <p>Stored at {@code users/{userId}/settings/workout}. The streak itself is still
 * derived client-side from the {@link ScheduledWorkout} calendar; the backend only
 * stores the threshold so it survives reinstall and syncs across devices.
 *
 * @param userId            owner
 * @param weeklyStreakTarget completed workouts per week required to keep the
 *                           streak alive (clamped to {@link #MIN_TARGET}..{@link #MAX_TARGET})
 * @param preferences       free-text standing instructions for the designer
 *                          (normalized via {@link #normalizePreferences}; null when unset)
 * @param updatedAt         server timestamp of the last write (null until stored)
 */
public record WorkoutSettings(
    String userId,
    int weeklyStreakTarget,
    String preferences,
    Instant updatedAt
) {

    /** Default weekly target when the user has never configured one. */
    public static final int DEFAULT_TARGET = 4;

    /** A week needs at least one workout to mean anything. */
    public static final int MIN_TARGET = 1;

    /** More than two-a-day, every day, is not a realistic threshold. */
    public static final int MAX_TARGET = 14;

    /**
     * Cap on the free-text preferences so a user can't (accidentally or not) blow
     * the designer prompt's token budget. Generous enough for a paragraph or two.
     */
    public static final int MAX_PREFERENCES_LENGTH = 2000;

    public static WorkoutSettings defaults(String userId) {
        return new WorkoutSettings(userId, DEFAULT_TARGET, null, null);
    }

    /** Clamp a requested target into the supported range. */
    public static int clampTarget(int target) {
        return Math.max(MIN_TARGET, Math.min(MAX_TARGET, target));
    }

    /**
     * Trim and length-cap free-text preferences. Null or blank collapses to null
     * so an empty box means "no standing preferences" rather than an empty string.
     */
    public static String normalizePreferences(String preferences) {
        if (preferences == null) {
            return null;
        }
        String trimmed = preferences.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_PREFERENCES_LENGTH
            ? trimmed.substring(0, MAX_PREFERENCES_LENGTH)
            : trimmed;
    }
}
