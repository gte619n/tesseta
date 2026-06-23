package com.gte619n.healthfitness.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.goals.GoalsRepository
import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GoalsFilter(val status: GoalStatus, val label: String) {
    ACTIVE(GoalStatus.ACTIVE, "Active"),
    COMPLETED(GoalStatus.COMPLETED, "Completed"),
    ARCHIVED(GoalStatus.ARCHIVED, "Archived"),
}

data class GoalsListUiState(
    val loading: Boolean = true,
    val filter: GoalsFilter = GoalsFilter.ACTIVE,
    val goals: List<Goal> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalsListViewModel @Inject constructor(
    private val repository: GoalsRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(GoalsFilter.ACTIVE)
    /** Bumped by [refresh] to force a re-subscription (and a fresh network fill). */
    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(GoalsListUiState())
    val state: StateFlow<GoalsListUiState> = _state.asStateFlow()

    init {
        // Observe the goals mirror reactively: the list renders instantly from
        // Room (offline-capable) and updates in place on an edit or a background
        // sync. Switching filter / pull-to-refresh re-subscribes the stream.
        viewModelScope.launch {
            combine(filter, refreshToken) { f, _ -> f }
                .flatMapLatest { f ->
                    _state.update { it.copy(loading = true, filter = f, error = null) }
                    repository.observeGoals(f.status)
                        .map<List<Goal>, Result<List<Goal>>> { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                }
                .collect { result ->
                    result
                        .onSuccess { goals ->
                            _state.update { it.copy(loading = false, goals = goals, error = null) }
                        }
                        .onFailure { e ->
                            _state.update {
                                it.copy(loading = false, error = e.message ?: "Failed to load goals")
                            }
                        }
                }
        }
    }

    fun setFilter(filter: GoalsFilter) {
        this.filter.value = filter
    }

    fun refresh() = refreshToken.update { it + 1 }
}
