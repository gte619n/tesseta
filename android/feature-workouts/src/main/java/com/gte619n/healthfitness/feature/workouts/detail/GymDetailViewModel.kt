package com.gte619n.healthfitness.feature.workouts.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest
import com.gte619n.healthfitness.feature.workouts.nav.GymDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the GymDetail screen. Loads the gym, resolves each equipment
 * id in parallel through [EquipmentRepository.get], and exposes the
 * combined state for the screen to render.
 *
 * Mutation methods (setDefault, delete, removeEquipment) refresh on
 * success. Optimistic flips on setDefault use a separate field so the
 * star inverts immediately even if the network is slow.
 */
@HiltViewModel
class GymDetailViewModel @Inject constructor(
    private val locationRepo: LocationRepository,
    private val equipmentRepo: EquipmentRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    val locationId: String = savedState.toRoute<GymDetailRoute>().locationId

    data class UiState(
        val loading: Boolean = true,
        val location: Location? = null,
        val equipment: List<Equipment> = emptyList(),
        val error: String? = null,
        val transientMessage: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            locationRepo.get(locationId).fold(
                onSuccess = { loc -> loadEquipment(loc) },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.localizedMessage ?: "Failed to load gym",
                        )
                    }
                },
            )
        }
    }

    private suspend fun loadEquipment(loc: Location) {
        val equipment = coroutineScope {
            loc.equipmentIds.map { id ->
                async { equipmentRepo.get(id).getOrNull() }
            }.awaitAll().filterNotNull()
        }
        _state.update {
            it.copy(loading = false, location = loc, equipment = equipment, error = null)
        }
    }

    fun setDefault() {
        val location = _state.value.location ?: return
        if (location.isDefault) return
        // Optimistic flip
        _state.update { it.copy(location = location.copy(isDefault = true)) }
        viewModelScope.launch {
            locationRepo.setDefault(locationId).fold(
                onSuccess = { refresh() },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            location = location, // rollback
                            transientMessage = e.localizedMessage ?: "Couldn't set default",
                        )
                    }
                },
            )
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            locationRepo.delete(locationId).fold(
                onSuccess = { onDeleted() },
                onFailure = { e ->
                    _state.update {
                        it.copy(transientMessage = e.localizedMessage ?: "Couldn't delete gym")
                    }
                },
            )
        }
    }

    fun removeEquipment(equipmentId: String) {
        val location = _state.value.location ?: return
        val nextIds = location.equipmentIds.filterNot { it == equipmentId }
        viewModelScope.launch {
            locationRepo.update(
                locationId = locationId,
                req = UpdateLocationRequest(equipmentIds = nextIds),
            ).fold(
                onSuccess = { loc -> loadEquipment(loc) },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            transientMessage = e.localizedMessage ?: "Couldn't remove equipment",
                        )
                    }
                },
            )
        }
    }

    fun consumeTransientMessage() {
        _state.update { it.copy(transientMessage = null) }
    }
}
