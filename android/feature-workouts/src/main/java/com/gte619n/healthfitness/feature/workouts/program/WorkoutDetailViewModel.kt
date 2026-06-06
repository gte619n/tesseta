package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val programTitle: String? = null,
    val phaseTitle: String? = null,
    val day: WorkoutDay? = null,
    val error: String? = null,
)

/**
 * Loads a single workout (one [WorkoutDay]) out of the deep program tree. The
 * program is fetched whole and the requested phase + day are located by id;
 * [WorkoutsRoutes.ARG_PHASE_ID] disambiguates because a dayId is only unique
 * within its phase's weekly microcycle. Read-only — the phone is a viewer.
 */
@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val programId: String =
        checkNotNull(savedStateHandle[WorkoutsRoutes.ARG_PROGRAM_ID]) {
            "WorkoutDetailViewModel requires a '${WorkoutsRoutes.ARG_PROGRAM_ID}' nav argument"
        }
    private val phaseId: String =
        checkNotNull(savedStateHandle[WorkoutsRoutes.ARG_PHASE_ID]) {
            "WorkoutDetailViewModel requires a '${WorkoutsRoutes.ARG_PHASE_ID}' nav argument"
        }
    private val dayId: String =
        checkNotNull(savedStateHandle[WorkoutsRoutes.ARG_DAY_ID]) {
            "WorkoutDetailViewModel requires a '${WorkoutsRoutes.ARG_DAY_ID}' nav argument"
        }

    private val _state = MutableStateFlow(WorkoutDetailUiState())
    val state: StateFlow<WorkoutDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.get(programId)
                .onSuccess { program ->
                    val phase = program.phases.firstOrNull { it.phaseId == phaseId }
                    val day = phase?.days?.firstOrNull { it.dayId == dayId }
                    if (day == null) {
                        _state.update {
                            it.copy(loading = false, error = "Workout not found")
                        }
                    } else {
                        _state.update {
                            it.copy(
                                loading = false,
                                programTitle = program.title,
                                phaseTitle = phase.title,
                                day = day,
                                error = null,
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "Failed to load workout")
                    }
                }
        }
    }
}
