package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProgramsListUiState(
    val loading: Boolean = true,
    val programs: List<WorkoutProgram> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ProgramsListViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProgramsListUiState())
    val state: StateFlow<ProgramsListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.list()
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
