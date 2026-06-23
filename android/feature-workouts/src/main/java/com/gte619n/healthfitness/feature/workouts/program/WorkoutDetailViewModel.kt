package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
@OptIn(ExperimentalCoroutinesApi::class)
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

    /** Bumped by [refresh] to force a re-subscription (and a fresh network refresh). */
    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(WorkoutDetailUiState())
    val state: StateFlow<WorkoutDetailUiState> = _state.asStateFlow()

    init {
        // Observe the deep program reactively and pick out this phase+day, so the
        // workout renders from the mirror (offline-capable) and updates in place
        // when the deep tree is upgraded or a background sync lands.
        viewModelScope.launch {
            refreshToken
                .flatMapLatest {
                    _state.update { it.copy(loading = true, error = null) }
                    repository.observeProgram(programId)
                        .map<WorkoutProgram?, Result<WorkoutProgram?>> { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                }
                .collect { result ->
                    result
                        .onSuccess { program ->
                            when {
                                program == null ->
                                    _state.update { it.copy(loading = false, error = "Failed to load workout") }
                                else -> {
                                    val phase = program.phases.firstOrNull { it.phaseId == phaseId }
                                    val day = phase?.days?.firstOrNull { it.dayId == dayId }
                                    when {
                                        day != null && phase != null ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    programTitle = program.title,
                                                    phaseTitle = phase.title,
                                                    day = day,
                                                    error = null,
                                                )
                                            }
                                        // A shallow row still upgrading to its deep
                                        // tree has no phases/days yet — keep loading
                                        // rather than flash "not found".
                                        program.phases.isEmpty() || phase?.days?.isEmpty() == true ->
                                            _state.update { it.copy(loading = true, error = null) }
                                        else ->
                                            _state.update { it.copy(loading = false, error = "Workout not found") }
                                    }
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

    fun refresh() = refreshToken.update { it + 1 }
}
