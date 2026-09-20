package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.adhoc.AdHocLibraryRepository
import com.gte619n.healthfitness.domain.workouts.adhoc.AdHocLibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutLibraryUiState(
    val loading: Boolean = true,
    val items: List<AdHocLibraryItem> = emptyList(),
)

/**
 * Reactive, offline-first Library list (IMPL-ADHOC-01): renders instantly from
 * the {@code adhocWorkouts} Room mirror and updates in place as the background
 * sync lands web-generated workouts. Read-only for now.
 */
@HiltViewModel
class WorkoutLibraryViewModel @Inject constructor(
    repository: AdHocLibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutLibraryUiState())
    val state: StateFlow<WorkoutLibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeLibrary()
                .catch { /* keep whatever we have; the empty state covers a cold mirror */ }
                .collect { items ->
                    _state.update { it.copy(loading = false, items = items) }
                }
        }
    }
}
