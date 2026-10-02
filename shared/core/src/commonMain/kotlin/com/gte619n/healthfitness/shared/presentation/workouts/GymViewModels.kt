package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.Amenity
import com.gte619n.healthfitness.shared.data.CreateLocationRequest
import com.gte619n.healthfitness.shared.data.Equipment
import com.gte619n.healthfitness.shared.data.EquipmentRepository
import com.gte619n.healthfitness.shared.data.HoursSlot
import com.gte619n.healthfitness.shared.data.Location
import com.gte619n.healthfitness.shared.data.LocationRepository
import com.gte619n.healthfitness.shared.data.PendingUpload
import com.gte619n.healthfitness.shared.data.UpdateLocationRequest
import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave D(iii) — shared ports of the Android gym ViewModels
 * (`feature-workouts/.../{GymsList,GymDetail,NewGym,EditGym}ViewModel`) plus the
 * shared gym-form state + validation (the Android `ui.LocationFormState` /
 * `validateLocationForm` / `hoursForWire`). Offline-first (ADR-0018): seed from
 * the Room mirror, then revalidate.
 */

// --- Shared gym-form state + validation ------------------------------------

/** Editable gym-form state, shared by New + Edit. Mirrors `LocationFormState`. */
data class LocationFormState(
    val name: String = "",
    val address: String = "",
    val is24Hours: Boolean = false,
    /** Per-day open/close; null = closed that day. */
    val hours: Map<DayOfWeek, HoursSlot?> = emptyMap(),
    val amenities: Set<Amenity> = emptySet(),
    val submitting: Boolean = false,
    val error: String? = null,
)

/** Returns an error message when the form is invalid, else null. */
fun validateLocationForm(form: LocationFormState): String? = when {
    form.name.isBlank() -> "Give your gym a name."
    else -> null
}

/** The hours map to send on the wire: omitted entirely when the gym is 24-hour. */
fun LocationFormState.hoursForWire(): Map<DayOfWeek, HoursSlot>? =
    if (is24Hours) null
    else hours.entries.mapNotNull { (day, slot) -> slot?.let { day to it } }.toMap().ifEmpty { null }

private fun Location.toFormState(): LocationFormState = LocationFormState(
    name = name,
    address = address.orEmpty(),
    is24Hours = is24Hours,
    hours = DayOfWeek.entries.associateWith { day -> hours?.get(day) },
    amenities = amenities.toSet(),
    submitting = false,
)

// --- Gyms list -------------------------------------------------------------

class GymsListViewModel(
    private val repo: LocationRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val locations: List<Location> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // Offline-first: seed instantly from the mirror so re-entry shows the
            // last-synced gyms with no spinner; only stay on Loading pre-first-sync.
            val cached = runCatching { repo.cachedList() }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                _state.update { it.copy(loading = false, locations = cached, error = null) }
            } else {
                _state.update { it.copy(loading = true, error = null) }
            }
            repo.list().fold(
                onSuccess = { locations ->
                    _state.update { it.copy(loading = false, locations = locations, error = null) }
                },
                onFailure = { e ->
                    _state.update {
                        if (it.locations.isNotEmpty()) it.copy(loading = false)
                        else it.copy(loading = false, error = e.message ?: "Failed to load gyms")
                    }
                },
            )
        }
    }
}

// --- Gym detail ------------------------------------------------------------

class GymDetailViewModel(
    private val locationId: String,
    private val repo: LocationRepository,
    private val equipmentRepo: EquipmentRepository,
) : ViewModel() {

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
            // Seed instantly from the mirror + catalog cache; then revalidate.
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
                    // Fetch each attached equipment's catalog row in parallel.
                    val equipment = coroutineScope {
                        location.equipmentIds
                            .map { id -> async { equipmentRepo.get(id).getOrNull() } }
                            .awaitAll()
                            .filterNotNull()
                    }
                    _state.update {
                        it.copy(loading = false, location = location, equipment = equipment, error = null)
                    }
                },
                onFailure = { e ->
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

// --- New gym ---------------------------------------------------------------

class NewGymViewModel(
    private val repo: LocationRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(LocationFormState())
    val form: StateFlow<LocationFormState> = _form.asStateFlow()

    fun update(state: LocationFormState) {
        _form.value = state
    }

    fun submit(onSuccess: (locationId: String) -> Unit) {
        val current = _form.value
        val error = validateLocationForm(current)
        if (error != null) {
            _form.update { it.copy(error = error) }
            return
        }
        _form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val req = CreateLocationRequest(
                name = current.name.trim(),
                address = current.address.trim().ifBlank { null },
                is24Hours = current.is24Hours,
                hours = current.hoursForWire(),
                amenities = current.amenities.map { it.id },
            )
            repo.create(req).fold(
                onSuccess = { location ->
                    _form.update { it.copy(submitting = false) }
                    onSuccess(location.locationId)
                },
                onFailure = { e ->
                    _form.update { it.copy(submitting = false, error = e.message ?: "Failed to create gym") }
                },
            )
        }
    }
}

// --- Edit gym --------------------------------------------------------------

class EditGymViewModel(
    private val locationId: String,
    private val repo: LocationRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(LocationFormState(submitting = true))
    val form: StateFlow<LocationFormState> = _form.asStateFlow()

    private val _coverPhotoUrl = MutableStateFlow<String?>(null)
    val coverPhotoUrl: StateFlow<String?> = _coverPhotoUrl.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            // Seed the form instantly from the mirror (submitting=false); only keep
            // the initial submitting gate when nothing is cached. Then revalidate.
            runCatching { repo.cached(locationId) }.getOrNull()?.let { location ->
                _coverPhotoUrl.value = location.coverPhotoUrl
                _form.value = location.toFormState()
            }
            repo.get(locationId).fold(
                onSuccess = { location ->
                    _coverPhotoUrl.value = location.coverPhotoUrl
                    _form.value = location.toFormState()
                },
                onFailure = { e ->
                    _form.update {
                        if (!it.submitting) it
                        else it.copy(submitting = false, error = e.message ?: "Failed to load gym")
                    }
                },
            )
        }
    }

    fun update(state: LocationFormState) {
        _form.value = state
    }

    fun submit(onSuccess: () -> Unit) {
        val current = _form.value
        val error = validateLocationForm(current)
        if (error != null) {
            _form.update { it.copy(error = error) }
            return
        }
        _form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val req = UpdateLocationRequest(
                name = current.name.trim(),
                address = current.address.trim().ifBlank { null },
                is24Hours = current.is24Hours,
                hours = current.hoursForWire(),
                amenities = current.amenities.map { it.id },
            )
            repo.update(locationId, req).fold(
                onSuccess = {
                    _form.update { it.copy(submitting = false) }
                    onSuccess()
                },
                onFailure = { e ->
                    _form.update { it.copy(submitting = false, error = e.message ?: "Failed to save gym") }
                },
            )
        }
    }

    fun uploadCoverPhoto(file: PendingUpload) {
        _uploading.value = true
        viewModelScope.launch {
            repo.uploadCoverPhoto(locationId, file).fold(
                onSuccess = { url ->
                    _uploading.value = false
                    if (url.isNotBlank()) {
                        _coverPhotoUrl.value = url
                    } else {
                        repo.get(locationId).onSuccess { _coverPhotoUrl.value = it.coverPhotoUrl }
                    }
                },
                onFailure = { e ->
                    _uploading.value = false
                    _form.update { it.copy(error = e.message ?: "Failed to upload photo") }
                },
            )
        }
    }

    fun deleteCoverPhoto() {
        viewModelScope.launch {
            repo.deleteCoverPhoto(locationId).onSuccess { _coverPhotoUrl.value = null }
        }
    }
}
