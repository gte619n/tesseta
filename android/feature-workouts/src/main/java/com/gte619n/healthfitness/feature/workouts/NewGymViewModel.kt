package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.CreateLocationRequest
import com.gte619n.healthfitness.domain.workouts.LocationRepository
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
class NewGymViewModel @Inject constructor(
    private val repo: LocationRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(LocationFormState())
    val form: StateFlow<LocationFormState> = _form.asStateFlow()

    fun update(state: LocationFormState) {
        _form.value = state
    }

    fun validate(state: LocationFormState): String? = validateLocationForm(state)

    fun submit(onSuccess: (locationId: String) -> Unit) {
        val current = _form.value
        val error = validate(current)
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
