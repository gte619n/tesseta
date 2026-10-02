package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.AdHocLibraryItem
import com.gte619n.healthfitness.shared.data.AdHocLibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave D — KMP port of the Android `WorkoutLibraryViewModel`
 * (IMPL-ADHOC-01). Reactive, offline-first Library list: renders instantly from
 * the `adhocWorkouts` mirror and updates in place as the background sync lands
 * web-generated workouts. Read-only. Archived items are filtered out for the
 * active list.
 */
data class WorkoutLibraryUiState(
    val loading: Boolean = true,
    val items: List<AdHocLibraryItem> = emptyList(),
)

class WorkoutLibraryViewModel(
    repository: AdHocLibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutLibraryUiState())
    val state: StateFlow<WorkoutLibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeLibrary()
                .catch { /* keep whatever we have; the empty state covers a cold mirror */ }
                .collect { items ->
                    _state.update {
                        it.copy(loading = false, items = items.filterNot { item -> item.archived })
                    }
                }
        }
    }
}
