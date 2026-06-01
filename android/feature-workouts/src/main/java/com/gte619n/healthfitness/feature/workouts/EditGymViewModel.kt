package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.domain.workouts.PendingUpload
import com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import com.gte619n.healthfitness.feature.workouts.ui.LocationFormState
import com.gte619n.healthfitness.feature.workouts.ui.hoursForWire
import com.gte619n.healthfitness.feature.workouts.ui.validateLocationForm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditGymViewModel @Inject constructor(
    private val repo: LocationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val locationId: String =
        savedStateHandle.get<String>(WorkoutsRoutes.ARG_LOCATION_ID)
            ?: error("locationId arg missing")

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
            repo.get(locationId).fold(
                onSuccess = { location ->
                    _coverPhotoUrl.value = location.coverPhotoUrl
                    val hours = DayOfWeek.entries.associateWith { day -> location.hours?.get(day) }
                    _form.value = LocationFormState(
                        name = location.name,
                        address = location.address.orEmpty(),
                        is24Hours = location.is24Hours,
                        hours = hours,
                        amenities = location.amenities.toSet(),
                        submitting = false,
                    )
                },
                onFailure = { e ->
                    _form.update { it.copy(submitting = false, error = e.message ?: "Failed to load gym") }
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
                        // Endpoint returned no URL; refetch to get the new cover.
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
