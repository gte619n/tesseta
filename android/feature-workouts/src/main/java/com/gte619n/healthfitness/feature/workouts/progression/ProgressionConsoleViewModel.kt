package com.gte619n.healthfitness.feature.workouts.progression

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.progression.ProgressionRepository
import com.gte619n.healthfitness.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.domain.workouts.progression.PatternReview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Progression console: this week's per-pattern review and the active
 * training block's parameters. Both are network-only reads (loaded together in
 * [load]); changing the mode PUTs the new parameters and refreshes from the
 * returned block. A failed initial load offers a retry.
 */
@HiltViewModel
class ProgressionConsoleViewModel @Inject constructor(
    private val repository: ProgressionRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val weekReview: List<PatternReview> = emptyList(),
        val block: BlockParameters? = null,
        val error: String? = null,
        /** A mode change is in flight (disables the toggle to avoid double taps). */
        val updatingMode: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    /** (Re)load both the week review and the block parameters. */
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
            _state.update {
                State(
                    loading = false,
                    weekReview = reviewResult.getOrDefault(emptyList()),
                    block = blockResult.getOrNull(),
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
