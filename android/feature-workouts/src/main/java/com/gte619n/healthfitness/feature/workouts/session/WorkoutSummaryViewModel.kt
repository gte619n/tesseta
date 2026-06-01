package com.gte619n.healthfitness.feature.workouts.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workout.WorkoutRepository
import com.gte619n.healthfitness.data.workout.WorkoutSessionController
import com.gte619n.healthfitness.domain.workout.CompletedSession
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutSummaryUiState(
    val loading: Boolean = true,
    val completed: CompletedSession? = null,
    val error: String? = null,
)

@HiltViewModel
class WorkoutSummaryViewModel @Inject constructor(
    private val repository: WorkoutRepository,
    private val controller: WorkoutSessionController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String =
        savedStateHandle.get<String>(WorkoutsRoutes.ARG_SESSION_ID)
            ?: error("sessionId arg missing")

    private val _state = MutableStateFlow(WorkoutSummaryUiState())
    val state: StateFlow<WorkoutSummaryUiState> = _state.asStateFlow()

    init {
        // The session is finished — clear the live controller (and its persisted
        // timer snapshot) so the foreground service tears down and a fresh
        // workout starts clean next time.
        controller.reset()
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val completed = repository.summary(sessionId)
                _state.update { it.copy(loading = false, completed = completed, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load summary") }
            }
        }
    }
}
