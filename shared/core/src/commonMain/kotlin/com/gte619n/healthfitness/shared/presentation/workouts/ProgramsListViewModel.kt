package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.WorkoutProgramRepository
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutProgram
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave D — KMP port of the Android `ProgramsListViewModel`.
 * Reactive, offline-first programs list off the mirror (renders instantly from
 * Room, updates in place on sync); pull-to-refresh re-subscribes. Follows the
 * reference [com.gte619n.healthfitness.shared.presentation.medications.MedicationsViewModel].
 */
data class ProgramsListUiState(
    val loading: Boolean = true,
    val programs: List<WorkoutProgram> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramsListViewModel(
    private val repository: WorkoutProgramRepository,
) : ViewModel() {

    /** Bumped by [refresh] to force a re-subscription (and a fresh network fill). */
    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(ProgramsListUiState())
    val state: StateFlow<ProgramsListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            refreshToken
                .flatMapLatest {
                    _state.update { it.copy(loading = true, error = null) }
                    repository.observePrograms()
                        .map<List<WorkoutProgram>, Result<List<WorkoutProgram>>> { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                }
                .collect { result ->
                    result
                        .onSuccess { programs ->
                            _state.update { it.copy(loading = false, programs = programs, error = null) }
                        }
                        .onFailure { e ->
                            _state.update {
                                it.copy(loading = false, error = e.message ?: "Failed to load programs")
                            }
                        }
                }
        }
    }

    fun refresh() = refreshToken.update { it + 1 }
}
