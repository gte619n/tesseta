package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
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

data class ProgramsListUiState(
    val loading: Boolean = true,
    val programs: List<WorkoutProgram> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgramsListViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
) : ViewModel() {

    /** Bumped by [refresh] to force a re-subscription (and a fresh network fill). */
    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(ProgramsListUiState())
    val state: StateFlow<ProgramsListUiState> = _state.asStateFlow()

    init {
        // Observe the programs mirror reactively: the list renders instantly from
        // Room (offline-capable) and updates in place when a background sync or a
        // deep-cache upgrade lands. Pull-to-refresh re-subscribes the stream.
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
