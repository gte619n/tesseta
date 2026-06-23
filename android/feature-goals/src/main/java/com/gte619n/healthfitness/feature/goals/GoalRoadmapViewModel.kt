package com.gte619n.healthfitness.feature.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.goals.GoalsRepository
import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.workouts.program.NutritionGuidance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val GOAL_ID_ARG = "goalId"

data class GoalRoadmapUiState(
    val loading: Boolean = true,
    val goal: GoalDeep? = null,
    val error: String? = null,
    /** stepIds with an in-flight mutation, so the UI can disable their checkbox. */
    val pendingStepIds: Set<String> = emptySet(),
    /** Nutrition guidance the goal's linked program can apply; null = no "Update nutrition" action. */
    val nutritionGuidance: NutritionGuidance? = null,
    val applyingNutrition: Boolean = false,
    /** One-shot: the macros just applied to the target, for a confirmation message. */
    val appliedNutrition: Macros? = null,
)

@HiltViewModel
class GoalRoadmapViewModel @Inject constructor(
    private val repository: GoalsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val goalId: String = checkNotNull(savedStateHandle[GOAL_ID_ARG]) {
        "GoalRoadmapViewModel requires a '$GOAL_ID_ARG' nav argument"
    }

    private val _state = MutableStateFlow(GoalRoadmapUiState())
    val state: StateFlow<GoalRoadmapUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val goal = repository.goalDeep(goalId)
                _state.update { it.copy(loading = false, goal = goal, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load goal")
                }
            }
        }
        // Best-effort: whether this goal's linked program has nutrition guidance
        // to apply (drives the "Update nutrition" action). A failure just hides it.
        viewModelScope.launch {
            val guidance = runCatching { repository.nutritionGuidance(goalId) }.getOrNull()
            _state.update { it.copy(nutritionGuidance = guidance) }
        }
    }

    /** Apply the goal's linked-program nutrition guidance as the user's macro target. */
    fun applyNutrition() {
        if (_state.value.applyingNutrition) return
        _state.update { it.copy(applyingNutrition = true, error = null) }
        viewModelScope.launch {
            try {
                val applied = repository.applyNutrition(goalId)
                _state.update { it.copy(applyingNutrition = false, appliedNutrition = applied) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        applyingNutrition = false,
                        error = e.message ?: "Couldn't update nutrition target",
                    )
                }
            }
        }
    }

    /** Clear the one-shot applied-nutrition result after the UI shows it. */
    fun consumeAppliedNutrition() {
        _state.update { it.copy(appliedNutrition = null) }
    }

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
