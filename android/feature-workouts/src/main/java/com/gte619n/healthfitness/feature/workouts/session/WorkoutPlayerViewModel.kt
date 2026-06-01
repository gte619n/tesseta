package com.gte619n.healthfitness.feature.workouts.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workout.WorkoutForegroundLauncher
import com.gte619n.healthfitness.data.workout.WorkoutPhase
import com.gte619n.healthfitness.data.workout.WorkoutSessionController
import com.gte619n.healthfitness.data.workout.WorkoutSessionState
import com.gte619n.healthfitness.domain.workout.Exercise
import com.gte619n.healthfitness.domain.workout.PlayerStep
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class PlayerPhase { Loading, Working, Resting, Paused, Finishing }

data class WorkoutPlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val steps: List<PlayerStep> = emptyList(),
    val index: Int = 0,
    val phase: PlayerPhase = PlayerPhase.Loading,
    val currentExercise: Exercise? = null,
    val secondsRemaining: Int? = null,
    val runningVolume: Double = 0.0,
    val setsCompleted: Int = 0,
    val totalSets: Int = 0,
    val finishedSessionId: String? = null,
    val error: String? = null,
) {
    val currentStep: PlayerStep? get() = steps.getOrNull(index)
}

// Thin adapter over the process-singleton WorkoutSessionController. The
// controller owns session state + the timer (so it survives screen open/close,
// backgrounding, and is shared with the foreground notification service); the
// ViewModel just maps the shared state into the screen's UI model and forwards
// user intents.
@HiltViewModel
class WorkoutPlayerViewModel @Inject constructor(
    private val controller: WorkoutSessionController,
    private val foreground: WorkoutForegroundLauncher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String =
        savedStateHandle.get<String>(WorkoutsRoutes.ARG_SESSION_ID)
            ?: error("sessionId arg missing")

    val state: StateFlow<WorkoutPlayerUiState> =
        controller.state
            .map { it.toUi() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, WorkoutPlayerUiState())

    init { controller.start(sessionId) }

    fun ensureForeground() = foreground.startForegroundSession()

    fun logCurrentSet(reps: Int?, weight: Double?) = controller.logCurrentSet(reps, weight)
    fun advance() = controller.advance()
    fun previous() = controller.previous()
    fun togglePause() = controller.togglePause()
    fun skipRest() = controller.skipRest()
    fun addRestTime(seconds: Int) = controller.addRestTime(seconds)
    fun finishNow() = controller.finish()

    private fun WorkoutSessionState.toUi() = WorkoutPlayerUiState(
        loading = loading,
        title = title,
        steps = steps,
        index = index,
        phase = phase.toUi(),
        currentExercise = currentExercise,
        secondsRemaining = secondsRemaining,
        runningVolume = runningVolume,
        setsCompleted = setsCompleted,
        totalSets = totalSets,
        finishedSessionId = finishedSessionId,
        error = error,
    )

    private fun WorkoutPhase.toUi(): PlayerPhase = when (this) {
        WorkoutPhase.Idle, WorkoutPhase.Loading -> PlayerPhase.Loading
        WorkoutPhase.Working -> PlayerPhase.Working
        WorkoutPhase.Resting -> PlayerPhase.Resting
        WorkoutPhase.Paused -> PlayerPhase.Paused
        WorkoutPhase.Finishing -> PlayerPhase.Finishing
    }
}
