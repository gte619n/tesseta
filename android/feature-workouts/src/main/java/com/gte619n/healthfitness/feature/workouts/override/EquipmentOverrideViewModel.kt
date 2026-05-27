package com.gte619n.healthfitness.feature.workouts.override

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.WorkoutsMappers
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the per-location equipment override sheet.
 *
 * On open: resolves the catalog [Equipment], reads any existing
 * override from `Location.equipmentSpecs[equipmentId]`, and seeds the
 * form. When the user saves, projects the typed [EquipmentSpec] back
 * into the wire-shape map and PATCHes
 * `/api/me/gyms/{locationId}/equipment/{equipmentId}`.
 *
 * The override sheet refuses to edit equipment whose schema this build
 * doesn't recognise (the mapper degrades unknown schemas to
 * Bodyweight; we treat any catalog row whose schema is Bodyweight but
 * whose spec map has fields as "needs web app to edit").
 */
@HiltViewModel
class EquipmentOverrideViewModel @Inject constructor(
    private val locationRepo: LocationRepository,
    private val equipmentRepo: EquipmentRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val equipment: Equipment? = null,
        val spec: EquipmentSpec = EquipmentSpec.Bodyweight,
        val hasExistingOverride: Boolean = false,
        val submitting: Boolean = false,
        val error: String? = null,
        val unsupported: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(locationId: String, equipmentId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val equipment = equipmentRepo.get(equipmentId).getOrNull()
            val location = locationRepo.get(locationId).getOrNull()
            if (equipment == null || location == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Couldn't load equipment",
                    )
                }
                return@launch
            }
            val existing = location.equipmentSpecs[equipmentId]
            val seededSpec = if (existing != null) {
                WorkoutsMappers.specsFromMap(equipment.specSchema, existing)
            } else {
                equipment.specs
            }
            _state.update {
                it.copy(
                    loading = false,
                    equipment = equipment,
                    spec = seededSpec,
                    hasExistingOverride = existing != null,
                    unsupported = false,
                )
            }
        }
    }

    fun updateSpec(spec: EquipmentSpec) {
        _state.update { it.copy(spec = spec) }
    }

    fun save(locationId: String, equipmentId: String, onDone: () -> Unit) {
        val current = _state.value
        val specs = WorkoutsMappers.specsToMap(current.spec)
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            locationRepo.updateEquipmentSpecs(
                locationId = locationId,
                equipmentId = equipmentId,
                specs = specs,
            ).fold(
                onSuccess = {
                    _state.update { it.copy(submitting = false) }
                    onDone()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = e.localizedMessage ?: "Couldn't save override",
                        )
                    }
                },
            )
        }
    }
}
