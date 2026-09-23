package com.gte619n.healthfitness.shared.presentation.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.NutritionDayRepository
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave C — shared macro-target editor VM. Port of Android
 * `feature-nutrition/NutritionTargetViewModel`. Loads the current target, saves an
 * edited one; `saved` drives a one-shot confirmation.
 */
data class NutritionTargetUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val target: Macros? = null,
    val error: String? = null,
)

class NutritionTargetViewModel(
    private val repository: NutritionDayRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NutritionTargetUiState())
    val state: StateFlow<NutritionTargetUiState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val target = repository.target()
                _state.update { it.copy(loading = false, target = target, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load target") }
            }
        }
    }

    fun save(target: Macros) {
        _state.update { it.copy(saving = true, saved = false, error = null) }
        viewModelScope.launch {
            try {
                val saved = repository.setTarget(target)
                _state.update { it.copy(saving = false, saved = true, target = saved, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Save failed") }
            }
        }
    }
}
