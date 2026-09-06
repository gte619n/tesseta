package com.gte619n.healthfitness.feature.workouts.progression

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.goals.GoalsRepository
import com.gte619n.healthfitness.data.workouts.progression.ProgressionRepository
import com.gte619n.healthfitness.domain.goals.GoalStatus
import com.gte619n.healthfitness.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.domain.workouts.progression.PatternReview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Progression console: this week's per-pattern review, per-lift
 * estimated strength (weights), the measured energy balance, the active goal it
 * serves, and the training block's parameters. Week review + block are the
 * required reads (a failure offers a retry); strength / energy / goal are
 * additive context that degrade quietly. Changing the mode PUTs the new
 * parameters and refreshes from the returned block.
 */
@HiltViewModel
class ProgressionConsoleViewModel @Inject constructor(
    private val repository: ProgressionRepository,
    private val goals: GoalsRepository,
) : ViewModel() {

    /** The goal this progression is serving (for the mode↔goal linkage). */
    data class ActiveGoal(val title: String, val domain: String)

    data class State(
        val loading: Boolean = true,
        val weekReview: List<PatternReview> = emptyList(),
        val block: BlockParameters? = null,
        val strength: List<ExerciseStrength> = emptyList(),
        val energy: EnergyBalance? = null,
        val goal: ActiveGoal? = null,
        val error: String? = null,
        /** A mode change is in flight (disables the toggle to avoid double taps). */
        val updatingMode: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    /** (Re)load the console. Week review + block are required; the rest is additive. */
    fun load() {
        viewModelScope.launch {
            _state.update { State(loading = true) }
            val reviewResult = repository.weekReview()
            val blockResult = repository.blockParameters()

            val error = reviewResult.exceptionOrNull() ?: blockResult.exceptionOrNull()
            if (error != null) {
                _state.update {
                    State(loading = false, error = error.message ?: "Couldn't load progression")
                }
                return@launch
            }

            // Additive context — never fail the screen on these.
            val strength = repository.strength().getOrDefault(emptyList())
            val energy = repository.energyBalance().getOrNull()
            val goal = runCatching { goals.goals(GoalStatus.ACTIVE) }.getOrNull()
                ?.firstOrNull()
                ?.let { ActiveGoal(it.title, it.domain.name) }

            _state.update {
                State(
                    loading = false,
                    weekReview = reviewResult.getOrDefault(emptyList()),
                    block = blockResult.getOrNull(),
                    strength = strength,
                    energy = energy,
                    goal = goal,
                )
            }
        }
    }

    /** Pin the training mode: PUT it, then adopt the refreshed block parameters. */
    fun updateMode(mode: String) {
        if (_state.value.updatingMode) return
        viewModelScope.launch {
            _state.update { it.copy(updatingMode = true) }
            repository.updateBlockParameters(mode = mode)
                .onSuccess { block ->
                    _state.update { it.copy(updatingMode = false, block = block) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(updatingMode = false, error = e.message ?: "Couldn't update mode")
                    }
                }
        }
    }
}
