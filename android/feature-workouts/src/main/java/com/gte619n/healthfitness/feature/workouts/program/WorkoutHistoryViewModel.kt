package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only Workout History: every COMPLETED session across all programs, newest
 * first, with the logged sets to review. Online-only (the history list isn't
 * mirrored); a failed load offers a retry.
 */
@HiltViewModel
class WorkoutHistoryViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val sessions: List<ScheduledWorkout> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repository.workoutHistory()
                .onSuccess { sessions ->
                    _state.update { it.copy(loading = false, sessions = sessions, error = null) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "Couldn't load workout history")
                    }
                }
        }
    }
}
