package com.gte619n.healthfitness.feature.workouts.edit

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.gte619n.healthfitness.domain.workouts.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest
import com.gte619n.healthfitness.feature.workouts.nav.EditGymRoute
import com.gte619n.healthfitness.feature.workouts.ui.LocationFormState
import com.gte619n.healthfitness.network.upload.UriUploads
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the EditGym screen. Loads the existing gym on init, exposes
 * the form state and cover-photo state separately, and PATCHes on
 * save. Cover-photo upload is its own action — the user picks a photo,
 * the upload runs immediately, and the screen updates the cover-photo
 * URL without forcing a Save.
 */
@HiltViewModel
class EditGymViewModel @Inject constructor(
    private val repo: LocationRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    val locationId: String = savedState.toRoute<EditGymRoute>().locationId

    data class UiState(
        val loading: Boolean = true,
        val form: LocationFormState = LocationFormState(),
        val coverPhotoUrl: String? = null,
        val uploadingPhoto: Boolean = false,
        val loadError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            repo.get(locationId).fold(
                onSuccess = { loc -> _state.update { it.fromLocation(loc) } },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = e.localizedMessage ?: "Failed to load gym",
                        )
                    }
                },
            )
        }
    }

    fun updateForm(transform: (LocationFormState) -> LocationFormState) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value.form
        val validation = validate(current)
        if (validation != null) {
            _state.update { it.copy(form = current.copy(error = validation)) }
            return
        }
        _state.update { it.copy(form = current.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val req = UpdateLocationRequest(
                name = current.name.trim(),
                address = current.address.trim().takeIf { it.isNotBlank() },
                is24Hours = current.is24Hours,
                hours = if (current.is24Hours) emptyMap()
                else current.hours.filterValues { it != null }.mapValues { it.value!! },
                amenities = current.amenities.map { it.id },
            )
            repo.update(locationId, req).fold(
                onSuccess = {
                    _state.update {
                        it.copy(form = it.form.copy(submitting = false))
                    }
                    onSuccess()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            form = it.form.copy(
                                submitting = false,
                                error = e.localizedMessage ?: "Failed to save gym",
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * Resolves the picked [uri] to a [PendingUpload] and POSTs it. The
     * uploaded LocationResponse refreshes the form's cover-photo URL.
     */
    fun uploadCoverPhoto(contentResolver: ContentResolver, uri: Uri) {
        val pending = UriUploads.from(contentResolver, uri)
        _state.update { it.copy(uploadingPhoto = true) }
        viewModelScope.launch {
            repo.uploadCoverPhoto(
                locationId = locationId,
                filename = pending.filename,
                mimeType = pending.mimeType,
                source = pending.source,
            ).fold(
                onSuccess = { loc ->
                    _state.update {
                        it.copy(
                            uploadingPhoto = false,
                            coverPhotoUrl = loc.coverPhotoUrl,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            uploadingPhoto = false,
                            form = it.form.copy(
                                error = e.localizedMessage ?: "Photo upload failed",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun deleteCoverPhoto() {
        viewModelScope.launch {
            repo.deleteCoverPhoto(locationId).fold(
                onSuccess = { loc -> _state.update { it.copy(coverPhotoUrl = loc.coverPhotoUrl) } },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            form = it.form.copy(
                                error = e.localizedMessage ?: "Couldn't remove photo",
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun UiState.fromLocation(loc: Location): UiState {
        val hours = DayOfWeek.entries.associateWith { loc.hours?.get(it) }
        return copy(
            loading = false,
            coverPhotoUrl = loc.coverPhotoUrl,
            form = LocationFormState(
                name = loc.name,
                address = loc.address.orEmpty(),
                is24Hours = loc.is24Hours,
                hours = hours,
                amenities = loc.amenities.toSet(),
            ),
        )
    }

    private fun validate(state: LocationFormState): String? = when {
        state.name.isBlank() -> "Name is required"
        !state.is24Hours && state.hours.values.all { it == null } ->
            "Set hours for at least one day, or enable 24-hour access"
        else -> null
    }
}
