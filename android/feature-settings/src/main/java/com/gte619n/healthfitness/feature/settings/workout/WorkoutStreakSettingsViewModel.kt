package com.gte619n.healthfitness.feature.settings.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.settings.WorkoutSettingsRepository
import com.gte619n.healthfitness.domain.workouts.WorkoutStreakSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the "Workout streak" settings section: the weekly completed-workout
 * target that keeps the landing streak alive. Source of truth is the backend
 * (synced across devices); this reads the [WorkoutSettingsRepository] cache flow
 * and writes changes through.
 */
@HiltViewModel
class WorkoutStreakSettingsViewModel @Inject constructor(
    private val repository: WorkoutSettingsRepository,
) : ViewModel() {

    val weeklyTarget: StateFlow<Int> =
        repository.weeklyStreakTarget.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET,
        )

    val minTarget: Int = WorkoutStreakSettings.MIN_WEEKLY_TARGET
    val maxTarget: Int = WorkoutStreakSettings.MAX_WEEKLY_TARGET

    init {
        // Pull the authoritative value from the backend into the cache on open.
        viewModelScope.launch { repository.refresh() }
    }

    /** Persist a new target (clamped). Ignored if unchanged or out of range. */
    fun setTarget(target: Int) {
        val clamped = WorkoutStreakSettings.clampTarget(target)
        if (clamped == weeklyTarget.value) return
        viewModelScope.launch { runCatching { repository.setWeeklyStreakTarget(clamped) } }
    }
}
