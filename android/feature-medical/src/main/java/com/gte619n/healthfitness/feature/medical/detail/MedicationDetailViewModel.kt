package com.gte619n.healthfitness.feature.medical.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.reminders.ReminderEngine
import com.gte619n.healthfitness.data.reminders.ReminderSettingsRepository
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationReminderOverride
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest
import com.gte619n.healthfitness.feature.medical.reminders.InlineReminderConfig
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

/**
 * Inline reminder edit state for the detail/edit screen (IMPL-STAB Workstream F
 * item 5): the editable per-med override plus the global defaults to show as the
 * fallback time. [saving] gates the save affordance.
 */
data class MedicationReminderUiState(
    val config: InlineReminderConfig = InlineReminderConfig(),
    val globalWindowTimes: Map<TimeWindow, String> = ReminderSettings.DEFAULT_WINDOW_TIMES,
    val saving: Boolean = false,
)

@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val medications: MedicationRepository,
    private val reminderSettings: ReminderSettingsRepository,
    private val reminderEngine: ReminderEngine,
    private val snackbar: SnackbarController,
) : ViewModel() {

    private val medicationId: String = checkNotNull(savedState["medicationId"]) {
        "medicationId route argument missing"
    }

    private val _state = MutableStateFlow<MedicationDetailUiState>(MedicationDetailUiState.Loading)
    val state: StateFlow<MedicationDetailUiState> = _state.asStateFlow()

    private val _reminder = MutableStateFlow(MedicationReminderUiState())
    val reminder: StateFlow<MedicationReminderUiState> = _reminder.asStateFlow()

    /** Emitted true after a successful delete so the screen can pop back. */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    init {
        refresh()
        loadReminder()
    }

    fun refresh() {
        viewModelScope.launch {
            // offline-fix: seed from the Room mirror instantly (no spinner) when we
            // have nothing shown yet, so opening a medication shows the last-synced
            // detail immediately; then revalidate from the network to graft on the
            // pull-only history (D9). A network failure keeps the cached detail on
            // screen — only a cold open with nothing mirrored shows the spinner/error.
            if (_state.value !is MedicationDetailUiState.Ready) {
                runCatching { medications.cachedDetail(medicationId) }.getOrNull()?.let { cached ->
                    if (_state.value !is MedicationDetailUiState.Ready) {
                        _state.value = MedicationDetailUiState.Ready(cached)
                    }
                }
            }
            runCatching { medications.get(medicationId) }
                .onSuccess { _state.value = MedicationDetailUiState.Ready(it) }
                .onFailure {
                    if (_state.value !is MedicationDetailUiState.Ready) {
                        _state.value =
                            MedicationDetailUiState.Error(it.message ?: "Could not load medication")
                    }
                }
        }
    }

    /** Load this med's current reminder override + global defaults into edit state. */
    private fun loadReminder() {
        viewModelScope.launch {
            runCatching { reminderSettings.get() }
                .onSuccess { s ->
                    val override = s.perMedication[medicationId] ?: MedicationReminderOverride()
                    _reminder.update {
                        it.copy(
                            config = InlineReminderConfig(
                                enabled = override.enabled,
                                times = override.times,
                            ),
                            globalWindowTimes = s.windowTimes,
                        )
                    }
                }
        }
    }

    fun onReminderChange(config: InlineReminderConfig) =
        _reminder.update { it.copy(config = config) }

    /**
     * Persist the edited reminder override onto the shared settings doc and
     * re-plan. A default config (enabled, no custom times) clears any existing
     * override so the doc stays minimal.
     */
    fun saveReminder() {
        val cfg = _reminder.value.config
        _reminder.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                val current = reminderSettings.get()
                val isDefault = cfg.enabled && cfg.times.isEmpty()
                val perMed = if (isDefault) {
                    current.perMedication - medicationId
                } else {
                    current.perMedication + (
                        medicationId to MedicationReminderOverride(
                            enabled = cfg.enabled, times = cfg.times,
                        )
                    )
                }
                reminderSettings.set(current.copy(perMedication = perMed))
            }.onSuccess {
                runCatching { reminderEngine.replan() }
                snackbar.show("Reminders updated")
            }.onFailure {
                snackbar.showError(it.message ?: "Couldn't update reminders")
            }
            _reminder.update { it.copy(saving = false) }
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

    /** Edit the schedule (frequency + weekly day-of-week selection). */
    fun updateSchedule(frequency: FrequencyConfig) {
        runAction {
            medications.update(medicationId, UpdateMedicationRequest(frequency = frequency))
            "Schedule updated"
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
