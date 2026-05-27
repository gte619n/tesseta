package com.gte619n.healthfitness.feature.workouts.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.CreateLocationRequest
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.feature.workouts.ui.LocationFormState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the New Gym screen. Holds the form state, validates on
 * submit, and POSTs to the backend. On success calls back with the
 * new locationId so the caller can navigate to the detail screen.
 */
@HiltViewModel
class NewGymViewModel @Inject constructor(
    private val repo: LocationRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(LocationFormState())
    val form: StateFlow<LocationFormState> = _form.asStateFlow()

    fun update(transform: (LocationFormState) -> LocationFormState) {
        _form.update(transform)
    }

    fun submit(onSuccess: (locationId: String) -> Unit) {
        val current = _form.value
        val validation = validate(current)
        if (validation != null) {
            _form.update { it.copy(error = validation) }
            return
        }
        _form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val req = CreateLocationRequest(
                name = current.name.trim(),
                address = current.address.trim().takeIf { it.isNotBlank() },
                is24Hours = current.is24Hours,
                hours = if (current.is24Hours) null
                else current.hours.filterValues { it != null }
                    .mapValues { it.value!! },
                amenities = current.amenities.map { it.id },
                equipmentIds = emptyList(),
            )
            repo.create(req).fold(
                onSuccess = { location ->
                    _form.update { it.copy(submitting = false) }
                    onSuccess(location.locationId)
                },
                onFailure = { e ->
                    _form.update {
                        it.copy(
                            submitting = false,
                            error = e.localizedMessage ?: "Failed to create gym",
                        )
                    }
                },
            )
        }
    }

    /** Returns an error message string or null when valid. */
    private fun validate(state: LocationFormState): String? = when {
        state.name.isBlank() -> "Name is required"
        !state.is24Hours && state.hours.values.all { it == null } ->
            "Set hours for at least one day, or enable 24-hour access"
        else -> null
    }
}
