package com.gte619n.healthfitness.feature.medical.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.data.reminders.ReminderEngine
import com.gte619n.healthfitness.data.reminders.ReminderSettingsRepository
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationReminderOverride
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReminderSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val enabled: Boolean = true,
    /** The user's default fire time ("HH:mm") per window. */
    val windowTimes: Map<TimeWindow, String> = ReminderSettings.DEFAULT_WINDOW_TIMES,
    /** Active medications, for the per-medication override list. */
    val medications: List<Medication> = emptyList(),
    val perMedication: Map<String, MedicationReminderOverride> = emptyMap(),
) {
    /** The resolved "HH:mm" a medication's window slot reminds at. */
    fun resolvedTime(medicationId: String, window: TimeWindow): String =
        perMedication[medicationId]?.times?.get(window)
            ?: windowTimes[window]
            ?: ReminderSettings.DEFAULT_WINDOW_TIMES.getValue(window)

    fun isCustom(medicationId: String, window: TimeWindow): Boolean =
        perMedication[medicationId]?.times?.containsKey(window) == true

    fun isMedEnabled(medicationId: String): Boolean =
        perMedication[medicationId]?.enabled ?: true
}

@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    private val settingsRepo: ReminderSettingsRepository,
    private val medications: MedicationRepository,
    private val engine: ReminderEngine,
    private val snackbar: SnackbarController,
) : ViewModel() {

    private val _state = MutableStateFlow(ReminderSettingsUiState())
    val state: StateFlow<ReminderSettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val settings = async { settingsRepo.get() }
                    val meds = async { medications.list(MedicationStatus.ACTIVE) }
                    val s = settings.await()
                    _state.update {
                        it.copy(
                            loading = false,
                            enabled = s.enabled,
                            windowTimes = s.windowTimes,
                            perMedication = s.perMedication,
                            medications = meds.await(),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Couldn't load reminder settings")
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = _state.update { it.copy(enabled = enabled) }

    fun setWindowTime(window: TimeWindow, time: String) =
        _state.update { it.copy(windowTimes = it.windowTimes + (window to time)) }

    fun setMedEnabled(medicationId: String, enabled: Boolean) = _state.update {
        val current = it.perMedication[medicationId] ?: MedicationReminderOverride()
        it.copy(
            perMedication = it.perMedication +
                (medicationId to current.copy(enabled = enabled)),
        )
    }

    /** Pin one medication slot to a custom time; null clears back to the default. */
    fun setMedTime(medicationId: String, window: TimeWindow, time: String?) = _state.update {
        val current = it.perMedication[medicationId] ?: MedicationReminderOverride()
        val times = if (time == null) current.times - window else current.times + (window to time)
        it.copy(perMedication = it.perMedication + (medicationId to current.copy(times = times)))
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                settingsRepo.set(
                    ReminderSettings(
                        enabled = s.enabled,
                        windowTimes = s.windowTimes,
                        // Drop no-op overrides so the stored doc stays minimal.
                        perMedication = s.perMedication.filterValues {
                            !it.enabled || it.times.isNotEmpty()
                        },
                    ),
                )
                engine.replan()
                snackbar.show("Reminders updated")
                _state.update { it.copy(saving = false) }
                onSaved()
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                snackbar.show(e.message ?: "Couldn't save reminder settings")
            }
        }
    }
}
