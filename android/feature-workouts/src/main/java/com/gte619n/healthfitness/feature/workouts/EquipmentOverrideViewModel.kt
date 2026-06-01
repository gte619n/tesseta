package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.feature.workouts.ui.toSpecMap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EquipmentOverrideViewModel @Inject constructor(
    private val locationRepo: LocationRepository,
    private val equipmentRepo: EquipmentRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val equipment: Equipment? = null,
        val specs: Map<String, Any?> = emptyMap(),
        val submitting: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Hydrates the form: starts from the catalog equipment's default specs,
     * then overlays any existing per-location override for this equipment.
     */
    fun load(locationId: String, equipmentId: String) {
        _state.update { UiState(loading = true) }
        viewModelScope.launch {
            val equipmentResult = equipmentRepo.get(equipmentId)
            val locationResult = locationRepo.get(locationId)
            val equipment = equipmentResult.getOrNull()
            if (equipment == null) {
                _state.update {
                    it.copy(loading = false, error = equipmentResult.exceptionOrNull()?.message ?: "Failed to load equipment")
                }
                return@launch
            }
            val existingOverride = locationResult.getOrNull()?.equipmentSpecs?.get(equipmentId)
            val hydrated = equipment.specs.toSpecMap() + (existingOverride ?: emptyMap())
            _state.update {
                it.copy(loading = false, equipment = equipment, specs = hydrated, error = null)
            }
        }
    }

    fun update(specs: Map<String, Any?>) {
        _state.update { it.copy(specs = specs) }
    }

    fun save(locationId: String, equipmentId: String, onDone: () -> Unit) {
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            locationRepo.updateEquipmentSpecs(locationId, equipmentId, _state.value.specs).fold(
                onSuccess = {
                    _state.update { it.copy(submitting = false) }
                    onDone()
                },
                onFailure = { e ->
                    _state.update { it.copy(submitting = false, error = e.message ?: "Failed to save override") }
                },
            )
        }
    }
}
