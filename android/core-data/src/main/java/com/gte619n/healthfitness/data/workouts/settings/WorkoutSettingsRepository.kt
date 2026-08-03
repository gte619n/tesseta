package com.gte619n.healthfitness.data.workouts.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.workouts.WorkoutStreakSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workoutSettingsStore by preferencesDataStore("hf-workout-settings")

/**
 * The user's workout preferences (currently just the weekly streak target).
 * Server-stored — the backend document is the source of truth — with a DataStore
 * cache so the Workouts landing can compute the streak instantly and offline,
 * before any network round-trip.
 *
 * [weeklyStreakTarget] is a reactive [Flow] so the landing recomputes the streak
 * the moment the target changes (a settings save, or a `workoutSettings` sync
 * push that triggers [refresh]).
 */
@Singleton
class WorkoutSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: WorkoutSettingsApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val targetKey = intPreferencesKey("weekly_streak_target")

    /** The cached weekly target, defaulting until the first successful [refresh]. */
    val weeklyStreakTarget: Flow<Int> =
        context.workoutSettingsStore.data.map { prefs ->
            prefs[targetKey] ?: WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET
        }

    /** Network read into the cache; a no-op on failure so offline keeps the last value. */
    suspend fun refresh() = withContext(io) {
        runCatching { api.get() }.getOrNull()?.let { cache(it.weeklyStreakTarget) }
        Unit
    }

    /** Persist a new target to the backend and the cache. Returns the stored value. */
    suspend fun setWeeklyStreakTarget(target: Int): Int = withContext(io) {
        val clamped = WorkoutStreakSettings.clampTarget(target)
        val stored = api.put(WorkoutSettingsDto(weeklyStreakTarget = clamped)).weeklyStreakTarget
        val resolved = WorkoutStreakSettings.clampTarget(stored ?: clamped)
        cache(resolved)
        resolved
    }

    private suspend fun cache(target: Int?) {
        val resolved = WorkoutStreakSettings.clampTarget(target ?: WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET)
        context.workoutSettingsStore.edit { it[targetKey] = resolved }
    }
}
