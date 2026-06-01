package com.gte619n.healthfitness.feature.workouts.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workout.WorkoutRepository
import com.gte619n.healthfitness.domain.workout.SessionStatus
import com.gte619n.healthfitness.domain.workout.WorkoutSession
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutOverviewUiState(
    val loading: Boolean = true,
    val session: WorkoutSession? = null,
    val starting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class WorkoutOverviewViewModel @Inject constructor(
    private val repository: WorkoutRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String =
        savedStateHandle.get<String>(WorkoutsRoutes.ARG_SESSION_ID)
            ?: error("sessionId arg missing")

    private val _state = MutableStateFlow(WorkoutOverviewUiState())
    val state: StateFlow<WorkoutOverviewUiState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val session = repository.get(sessionId)
                _state.update { it.copy(loading = false, session = session, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load workout") }
            }
        }
    }

    /** Start (or resume) the session, then hand the id to the player. */
    fun start(onStarted: (String) -> Unit) {
        val session = _state.value.session ?: return
        // Already running — go straight to the player.
        if (session.status == SessionStatus.IN_PROGRESS) {
            onStarted(session.sessionId)
            return
        }
        _state.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val started = repository.start(session.sessionId)
                _state.update { it.copy(starting = false, session = started) }
                onStarted(started.sessionId)
            } catch (e: Exception) {
                _state.update { it.copy(starting = false, error = e.message ?: "Couldn't start workout") }
            }
        }
    }
}
