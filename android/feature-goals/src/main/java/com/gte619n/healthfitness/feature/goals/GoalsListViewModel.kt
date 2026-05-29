package com.gte619n.healthfitness.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.goals.GoalsRepository
import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@HiltViewModel
class GoalsListViewModel @Inject constructor(
    private val repository: GoalsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GoalsListUiState())
    val state: StateFlow<GoalsListUiState> = _state.asStateFlow()

    init {
        load(GoalsFilter.ACTIVE)
    }

    fun setFilter(filter: GoalsFilter) {
        if (filter == _state.value.filter) return
        load(filter)
    }

    fun refresh() = load(_state.value.filter)

    private fun load(filter: GoalsFilter) {
        _state.update { it.copy(loading = true, filter = filter, error = null) }
        viewModelScope.launch {
            try {
                val goals = repository.goals(filter.status)
                _state.update { it.copy(loading = false, goals = goals, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load goals")
                }
            }
        }
    }
}
