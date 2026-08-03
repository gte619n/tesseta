package com.gte619n.healthfitness.domain.workouts

/**
 * Bounds and default for the weekly workout-streak target — the number of
 * completed workouts a calendar week must contain to keep the "consecutive
 * weeks" streak on the Workouts landing alive. Mirrors the backend
 * {@code WorkoutSettings} constants so both clients clamp identically.
 */
object WorkoutStreakSettings {

    const val DEFAULT_WEEKLY_TARGET = 4

    const val MIN_WEEKLY_TARGET = 1

    const val MAX_WEEKLY_TARGET = 14

    fun clampTarget(target: Int): Int =
        target.coerceIn(MIN_WEEKLY_TARGET, MAX_WEEKLY_TARGET)
}
