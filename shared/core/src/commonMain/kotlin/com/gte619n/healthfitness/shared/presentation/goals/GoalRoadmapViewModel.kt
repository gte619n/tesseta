package com.gte619n.healthfitness.shared.presentation.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.GoalsRepository
import com.gte619n.healthfitness.shared.domain.goals.GoalDeep
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
 * IMPL-IOS-01 Phase 3 Wave E3 (Goals) — shared port of the Android
 * `feature-goals/.../GoalRoadmapViewModel`. Reactive deep-goal (phases + steps)
 * roadmap: assembles from the mirror and updates in place on an optimistic
 * step-done edit or a background sync, without blocking the first cached render.
 *
 * The [goalId] is a plain constructor param (the iOS route passes it in / the
 * Android `SavedStateHandle` nav-arg is replaced by construction), matching the
 * D2 "plain constructor" rule for KMP ViewModels.
 *
 * NOTE (parity scope): the Android VM also drives an "Update nutrition" action
 * off the goal's linked training program (`nutritionGuidance` / `applyNutrition`,
 * pulling `domain.workouts.program.NutritionGuidance` + `domain.nutrition.Macros`).
 * Those cross-domain shared types belong to the Workouts / Nutrition verticals,
 * not Goals, so that action is intentionally omitted here and lands when those
 * shared domains are ported.
 */
data class GoalRoadmapUiState(
    val loading: Boolean = true,
    val goal: GoalDeep? = null,
    val error: String? = null,
    /** stepIds with an in-flight mutation, so the UI can disable their checkbox. */
    val pendingStepIds: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class GoalRoadmapViewModel(
    private val goalId: String,
    private val repository: GoalsRepository,
) : ViewModel() {

    /** Bumped by [load] to force a re-subscription (and a fresh network refresh). */
    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(GoalRoadmapUiState())
    val state: StateFlow<GoalRoadmapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            refreshToken
                .flatMapLatest {
                    _state.update { it.copy(loading = true, error = null) }
                    repository.observeGoalDeep(goalId)
                        .map<GoalDeep?, Result<GoalDeep?>> { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                }
                .collect { result ->
                    result
                        .onSuccess { goal ->
                            _state.update { it.copy(loading = false, goal = goal, error = null) }
                        }
                        .onFailure { e ->
                            _state.update {
                                it.copy(loading = false, error = e.message ?: "Failed to load goal")
                            }
                        }
                }
        }
    }

    fun load() = refreshToken.update { it + 1 }

    fun toggleStep(phaseId: String, stepId: String, done: Boolean) {
        mutate(stepId) { repository.setStepDone(goalId, phaseId, stepId, done) }
    }

    fun resetStepToAuto(phaseId: String, stepId: String) {
        mutate(stepId) { repository.resetStepToAuto(goalId, phaseId, stepId) }
    }

    private fun mutate(stepId: String, block: suspend () -> GoalDeep) {
        _state.update { it.copy(pendingStepIds = it.pendingStepIds + stepId) }
        viewModelScope.launch {
            try {
                val updated = block()
                _state.update {
                    it.copy(goal = updated, pendingStepIds = it.pendingStepIds - stepId, error = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        pendingStepIds = it.pendingStepIds - stepId,
                        error = e.message ?: "Update failed",
                    )
                }
            }
        }
    }
}
