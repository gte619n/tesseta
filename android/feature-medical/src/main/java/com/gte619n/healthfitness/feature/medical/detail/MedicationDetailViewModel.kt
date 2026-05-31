package com.gte619n.healthfitness.feature.medical.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface MedicationDetailUiState {
    data object Loading : MedicationDetailUiState
    data class Ready(
        val detail: MedicationDetail,
        val actionInFlight: Boolean = false,
    ) : MedicationDetailUiState
    data class Error(val message: String) : MedicationDetailUiState
}

@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val medications: MedicationRepository,
    private val snackbar: SnackbarController,
) : ViewModel() {

    private val medicationId: String = checkNotNull(savedState["medicationId"]) {
        "medicationId route argument missing"
    }

    private val _state = MutableStateFlow<MedicationDetailUiState>(MedicationDetailUiState.Loading)
    val state: StateFlow<MedicationDetailUiState> = _state.asStateFlow()

    /** Emitted true after a successful delete so the screen can pop back. */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = MedicationDetailUiState.Loading
        viewModelScope.launch {
            runCatching { medications.get(medicationId) }
                .onSuccess { _state.value = MedicationDetailUiState.Ready(it) }
                .onFailure {
                    _state.value =
                        MedicationDetailUiState.Error(it.message ?: "Could not load medication")
                }
        }
    }

    /** [PR#8] Change dose effective on a date → rebuilds the detail. */
    fun changeDose(dose: Double, unit: String?, startDate: LocalDate?, notes: String?) {
        runAction {
            medications.changeDose(
                medicationId,
                ChangeDoseRequest(dose = dose, unit = unit, startDate = startDate, changeNotes = notes),
            )
            "Dose updated"
        }
    }

    /** [PR#8] Edit the medication start date (shifts earliest dosing period). */
    fun editStartDate(startDate: LocalDate) {
        runAction {
            medications.update(medicationId, UpdateMedicationRequest(startDate = startDate))
            "Start date updated"
        }
    }

    /** [PR#8] Discontinue with reason, notes and an explicit end date. */
    fun discontinue(reason: DiscontinueReason, notes: String?, endDate: LocalDate) {
        runAction {
            medications.discontinue(medicationId, reason, notes, endDate)
            "Medication discontinued"
        }
    }

    /** [PR#8] Resume a discontinued medication from a resume date. */
    fun reactivate(resumeDate: LocalDate?) {
        runAction {
            medications.reactivate(medicationId, resumeDate)
            "Medication resumed"
        }
    }

    fun delete() {
        val current = _state.value
        if (current is MedicationDetailUiState.Ready) {
            _state.value = current.copy(actionInFlight = true)
        }
        viewModelScope.launch {
            runCatching { medications.delete(medicationId) }
                .onSuccess {
                    snackbar.show("Medication deleted")
                    _deleted.value = true
                }
                .onFailure {
                    snackbar.showError(it.message ?: "Could not delete medication")
                    setActionInFlight(false)
                }
        }
    }

    /**
     * Run a write action that returns a success message, then refresh detail.
     * Surfaces failures via the snackbar and clears the in-flight flag.
     */
    private fun runAction(block: suspend () -> String) {
        setActionInFlight(true)
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { message ->
                    snackbar.show(message)
                    refresh()
                }
                .onFailure {
                    snackbar.showError(it.message ?: "Action failed")
                    setActionInFlight(false)
                }
        }
    }

    private fun setActionInFlight(inFlight: Boolean) {
        _state.update { s ->
            if (s is MedicationDetailUiState.Ready) s.copy(actionInFlight = inFlight) else s
        }
    }
}
