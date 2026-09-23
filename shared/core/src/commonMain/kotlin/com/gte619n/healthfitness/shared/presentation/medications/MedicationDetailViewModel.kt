package com.gte619n.healthfitness.shared.presentation.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.MedicationCrudRepository
import com.gte619n.healthfitness.shared.data.ReminderSettingsRepository
import com.gte619n.healthfitness.shared.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.shared.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.shared.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.shared.domain.medications.InlineReminderConfig
import com.gte619n.healthfitness.shared.domain.medications.MedicationDetail
import com.gte619n.healthfitness.shared.domain.medications.MedicationReminderOverride
import com.gte619n.healthfitness.shared.domain.medications.ReminderSettings
import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import com.gte619n.healthfitness.shared.domain.medications.UpdateMedicationRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave B — port of the Android
 * `feature-medical/.../detail/MedicationDetailViewModel.kt` to KMP `commonMain`,
 * following the reference [MedicationsViewModel] shape (D2). `medicationId` is a
 * plain constructor arg (the platform navigation passes the route id) rather than
 * a Hilt `SavedStateHandle`. Offline-first detail (seed from the mirror, then
 * revalidate for the pull-only history, D9), dose/schedule/start-date/discontinue/
 * reactivate/delete write actions, and the inline reminder edit are all shared.
 *
 * Transient result/error messages surface via [message] (a nullable one-shot),
 * replacing the Android `SnackbarController` to stay KMP-clean; [deleted] flips
 * true after a successful delete so the platform view pops back. After a reminder
 * edit the platform re-plans (D9 `LocalReminderScheduler.replan()`), mirroring
 * Android's `reminderEngine.replan()` — supplied as [onReplan].
 */
sealed interface MedicationDetailUiState {
    data object Loading : MedicationDetailUiState
    data class Ready(
        val detail: MedicationDetail,
        val actionInFlight: Boolean = false,
    ) : MedicationDetailUiState
    data class Error(val message: String) : MedicationDetailUiState
}

/** Inline reminder edit state: the editable override + global defaults + saving gate. */
data class MedicationReminderUiState(
    val config: InlineReminderConfig = InlineReminderConfig(),
    val globalWindowTimes: Map<TimeWindow, String> = ReminderSettings.DEFAULT_WINDOW_TIMES,
    val saving: Boolean = false,
)

class MedicationDetailViewModel(
    private val medicationId: String,
    private val medications: MedicationCrudRepository,
    private val reminderSettings: ReminderSettingsRepository,
    private val onReplan: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<MedicationDetailUiState>(MedicationDetailUiState.Loading)
    val state: StateFlow<MedicationDetailUiState> = _state.asStateFlow()

    private val _reminder = MutableStateFlow(MedicationReminderUiState())
    val reminder: StateFlow<MedicationReminderUiState> = _reminder.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
        loadReminder()
    }

    fun refresh() {
        viewModelScope.launch {
            // offline-fix: seed from the mirror instantly (no spinner) when nothing is
            // shown yet, then revalidate to graft on the pull-only history (D9). A
            // network failure keeps the cached detail; only a cold open with nothing
            // mirrored shows the spinner/error.
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
     * Persist the edited reminder override onto the shared settings doc and re-plan.
     * A default config (enabled, no custom times) clears any override so the doc
     * stays minimal.
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
                        medicationId to MedicationReminderOverride(enabled = cfg.enabled, times = cfg.times)
                    )
                }
                reminderSettings.set(current.copy(perMedication = perMed))
            }.onSuccess {
                runCatching { onReplan() }
                _message.value = "Reminders updated"
            }.onFailure {
                _message.value = it.message ?: "Couldn't update reminders"
            }
            _reminder.update { it.copy(saving = false) }
        }
    }

    /**
     * Save dose and/or schedule in one action; only changed parts are dispatched
     * (a non-null [dose] posts a dated dose change, a non-null [frequency] updates
     * the schedule). Callers pass null for a section they left untouched.
     */
    fun saveDoseAndSchedule(
        dose: Double?,
        unit: String?,
        effectiveDate: LocalDate?,
        notes: String?,
        frequency: FrequencyConfig?,
    ) {
        if (dose == null && frequency == null) return
        runAction {
            if (dose != null) {
                medications.changeDose(
                    medicationId,
                    ChangeDoseRequest(dose = dose, unit = unit, startDate = effectiveDate, changeNotes = notes),
                )
            }
            if (frequency != null) {
                medications.update(medicationId, UpdateMedicationRequest(frequency = frequency))
            }
            when {
                dose != null && frequency != null -> "Dose & schedule updated"
                dose != null -> "Dose updated"
                else -> "Schedule updated"
            }
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
        setActionInFlight(true)
        viewModelScope.launch {
            runCatching { medications.delete(medicationId) }
                .onSuccess {
                    _message.value = "Medication deleted"
                    _deleted.value = true
                }
                .onFailure {
                    _message.value = it.message ?: "Could not delete medication"
                    setActionInFlight(false)
                }
        }
    }

    fun consumeMessage() { _message.value = null }

    private fun runAction(block: suspend () -> String) {
        setActionInFlight(true)
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { message ->
                    _message.value = message
                    refresh()
                }
                .onFailure {
                    _message.value = it.message ?: "Action failed"
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
