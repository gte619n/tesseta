package com.gte619n.healthfitness.data.workouts.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * The user's standing workout preferences: the weekly streak target and the
 * free-text designer preferences (exercises to avoid, injury notes, etc.).
 * Server-stored — the backend document is the source of truth — with a DataStore
 * cache so the Workouts landing can compute the streak instantly and offline, and
 * the preferences editor can show the current text before any network round-trip.
 *
 * [weeklyStreakTarget] and [preferences] are reactive [Flow]s so screens recompute
 * the moment they change (a settings save, or a `workoutSettings` sync push that
 * triggers [refresh]).
 */
@Singleton
class WorkoutSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: WorkoutSettingsApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val targetKey = intPreferencesKey("weekly_streak_target")
    private val preferencesKey = stringPreferencesKey("designer_preferences")

    /** The cached weekly target, defaulting until the first successful [refresh]. */
    val weeklyStreakTarget: Flow<Int> =
        context.workoutSettingsStore.data.map { prefs ->
            prefs[targetKey] ?: WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET
        }

    /** The cached free-text designer preferences; empty string when none are set. */
    val preferences: Flow<String> =
        context.workoutSettingsStore.data.map { prefs -> prefs[preferencesKey].orEmpty() }

    /**
     * Authoritative network read into the cache; a no-op on failure so offline
     * keeps the last value. Unlike a targeted save this overwrites BOTH fields
     * (treating a null from the server as "unset"), so a change made on another
     * device — including clearing the preferences — propagates on the next pull.
     */
    suspend fun refresh() = withContext(io) {
        val dto = runCatching { api.get() }.getOrNull() ?: return@withContext
        context.workoutSettingsStore.edit { prefs ->
            prefs[targetKey] = WorkoutStreakSettings.clampTarget(
                dto.weeklyStreakTarget ?: WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET)
            prefs[preferencesKey] = dto.preferences.orEmpty().trim()
        }
    }

    /** Persist a new target to the backend and the cache. Returns the stored value. */
    suspend fun setWeeklyStreakTarget(target: Int): Int = withContext(io) {
        val clamped = WorkoutStreakSettings.clampTarget(target)
        val stored = api.put(WorkoutSettingsDto(weeklyStreakTarget = clamped))
        cacheFrom(stored, fallbackTarget = clamped)
        WorkoutStreakSettings.clampTarget(stored.weeklyStreakTarget ?: clamped)
    }

    /**
     * Persist new free-text preferences to the backend and the cache. A blank value
     * clears them. Returns the stored text (empty when cleared).
     */
    suspend fun setPreferences(preferences: String): String = withContext(io) {
        val trimmed = preferences.trim()
        val stored = api.put(WorkoutSettingsDto(preferences = trimmed))
        cacheFrom(stored, fallbackPreferences = trimmed)
        stored.preferences.orEmpty()
    }

    /** Cache whatever the server echoed back, falling back to the value we sent. */
    private suspend fun cacheFrom(
        dto: WorkoutSettingsDto,
        fallbackTarget: Int? = null,
        fallbackPreferences: String? = null,
    ) = cache(
        target = dto.weeklyStreakTarget ?: fallbackTarget,
        preferences = dto.preferences ?: fallbackPreferences,
    )

    private suspend fun cache(target: Int?, preferences: String?) {
        context.workoutSettingsStore.edit { prefs ->
            if (target != null) {
                prefs[targetKey] = WorkoutStreakSettings.clampTarget(target)
            }
            if (preferences != null) {
                prefs[preferencesKey] = preferences.trim()
            }
        }
    }
}
