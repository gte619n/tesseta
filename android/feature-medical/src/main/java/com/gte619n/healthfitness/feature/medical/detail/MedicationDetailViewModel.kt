package com.gte619n.healthfitness.feature.medical.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest
import com.gte619n.healthfitness.feature.medical.nav.MedicationDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the medication-detail screen — initial load + edit / discontinue
 * / delete actions. Edit posts a `PUT` and re-reads the detail to pick up
 * the new history entry the backend writes when the dose changes.
 *
 * `actionInFlight` is a single boolean rather than a per-action sealed
 * type because the UI disables the entire action row while any of the
 * three mutating calls is in flight.
 */
@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val medications: MedicationRepository,
) : ViewModel() {

    private val medicationId: String = savedState.toRoute<MedicationDetailRoute>().medicationId

    private val _state = MutableStateFlow<MedicationDetailUiState>(MedicationDetailUiState.Loading)
    val state: StateFlow<MedicationDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = MedicationDetailUiState.Loading
        viewModelScope.launch {
            runCatching { medications.get(medicationId) }
                .onSuccess { detail ->
                    _state.value = MedicationDetailUiState.Ready(detail = detail)
                }
                .onFailure { e ->
                    _state.value = MedicationDetailUiState.Error(e.message ?: "Unknown error")
                }
        }
    }

    fun discontinue(reason: DiscontinueReason, notes: String?, onDone: () -> Unit = {}) {
        val current = _state.value as? MedicationDetailUiState.Ready ?: return
        _state.update { current.copy(actionInFlight = true) }
        viewModelScope.launch {
            runCatching { medications.discontinue(medicationId, reason, notes) }
                .onSuccess {
                    _state.update { current.copy(actionInFlight = false) }
                    onDone()
                }
                .onFailure { e ->
                    _state.update { current.copy(actionInFlight = false, error = e.message) }
                }
        }
    }

    fun delete(onDone: () -> Unit = {}) {
        val current = _state.value as? MedicationDetailUiState.Ready ?: return
        _state.update { current.copy(actionInFlight = true) }
        viewModelScope.launch {
            runCatching { medications.delete(medicationId) }
                .onSuccess { onDone() }
                .onFailure { e ->
                    _state.update { current.copy(actionInFlight = false, error = e.message) }
                }
        }
    }

    fun updateDose(newDose: Double, changeNotes: String? = null) {
        val current = _state.value as? MedicationDetailUiState.Ready ?: return
        _state.update { current.copy(actionInFlight = true) }
        viewModelScope.launch {
            runCatching {
                medications.update(
                    medicationId,
                    UpdateMedicationRequest(dose = newDose, changeNotes = changeNotes),
                )
            }
                .onSuccess { load() } // re-fetch so the new history entry appears
                .onFailure { e ->
                    _state.update { current.copy(actionInFlight = false, error = e.message) }
                }
        }
    }
}

sealed interface MedicationDetailUiState {
    data object Loading : MedicationDetailUiState
    data class Ready(
        val detail: MedicationDetail,
        val actionInFlight: Boolean = false,
        val error: String? = null,
    ) : MedicationDetailUiState
    data class Error(val message: String) : MedicationDetailUiState
}
