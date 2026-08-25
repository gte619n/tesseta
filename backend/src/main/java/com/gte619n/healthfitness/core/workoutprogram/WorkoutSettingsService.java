package com.gte619n.healthfitness.core.workoutprogram;

import org.springframework.stereotype.Service;

/**
 * Reads and writes the user's workout-preferences settings. Reads always return
 * a complete object: a user who never configured anything gets the defaults.
 */
@Service
public class WorkoutSettingsService {

    private final WorkoutSettingsRepository repository;

    public WorkoutSettingsService(WorkoutSettingsRepository repository) {
        this.repository = repository;
    }

    public WorkoutSettings get(String userId) {
        requireUser(userId);
        return repository.find(userId)
            .orElseGet(() -> WorkoutSettings.defaults(userId));
    }

    /**
     * Set the weekly streak target, preserving the standing preferences. Returns
     * the stored value.
     */
    public WorkoutSettings setWeeklyStreakTarget(String userId, int weeklyStreakTarget) {
        requireUser(userId);
        WorkoutSettings current = get(userId);
        WorkoutSettings settings = new WorkoutSettings(
            userId, WorkoutSettings.clampTarget(weeklyStreakTarget), current.preferences(), null);
        repository.save(settings);
        return settings;
    }

    /**
     * Set the free-text standing preferences, preserving the streak target. A null
     * or blank value clears them. Returns the stored value.
     */
    public WorkoutSettings setPreferences(String userId, String preferences) {
        requireUser(userId);
        WorkoutSettings current = get(userId);
        WorkoutSettings settings = new WorkoutSettings(
            userId, current.weeklyStreakTarget(),
            WorkoutSettings.normalizePreferences(preferences), null);
        repository.save(settings);
        return settings;
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
    }
}
