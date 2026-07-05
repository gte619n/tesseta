package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.data.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.data.workouts.LocationRepository
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GymDetailViewModel @Inject constructor(
    private val repo: LocationRepository,
    private val equipmentRepo: EquipmentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val locationId: String =
        savedStateHandle.get<String>(WorkoutsRoutes.ARG_LOCATION_ID)
            ?: error("locationId arg missing")

    data class UiState(
        val loading: Boolean = true,
        val location: Location? = null,
        val equipment: List<Equipment> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // Offline-first (ADR-0018): seed instantly from the mirror + catalog
            // cache so re-entry paints the gym and its equipment with no spinner.
            // Only stay on Loading when nothing is cached yet. Then revalidate.
            val cachedLocation = runCatching { repo.cached(locationId) }.getOrNull()
            if (cachedLocation != null) {
                val cachedEquipment = cachedLocation.equipmentIds
                    .mapNotNull { id -> runCatching { equipmentRepo.cached(id) }.getOrNull() }
                _state.update {
                    it.copy(loading = false, location = cachedLocation, equipment = cachedEquipment, error = null)
                }
            } else {
                _state.update { it.copy(loading = true, error = null) }
            }
            repo.get(locationId).fold(
                onSuccess = { location ->
                    // Fetch each attached equipment's catalog row in parallel
                    // (cache-first per repo, so this is offline-tolerant).
                    val equipment = location.equipmentIds
                        .map { id -> async { equipmentRepo.get(id).getOrNull() } }
                        .awaitAll()
                        .filterNotNull()
                    _state.update {
                        it.copy(loading = false, location = location, equipment = equipment, error = null)
                    }
                },
                onFailure = { e ->
                    // Keep any seeded gym on screen; only surface an error when
                    // there's nothing to show.
                    _state.update {
                        if (it.location != null) it.copy(loading = false)
                        else it.copy(loading = false, error = e.message ?: "Failed to load gym")
                    }
                },
            )
        }
    }

    /** Optimistically flips isDefault on this location; rolls back on failure. */
    fun setDefault() {
        val previous = _state.value.location ?: return
        if (previous.isDefault) return
        _state.update { it.copy(location = previous.copy(isDefault = true)) }
        viewModelScope.launch {
            repo.setDefault(locationId).onFailure {
                _state.update { s -> s.copy(location = previous, error = "Failed to set default") }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repo.delete(locationId).fold(
                onSuccess = { onDeleted() },
                onFailure = { e -> _state.update { it.copy(error = e.message ?: "Failed to delete gym") } },
            )
        }
    }

    fun removeEquipment(equipmentId: String) {
        viewModelScope.launch {
            repo.removeEquipment(locationId, equipmentId).fold(
                onSuccess = { refresh() },
                onFailure = { e -> _state.update { it.copy(error = e.message ?: "Failed to remove equipment") } },
            )
        }
    }
}
