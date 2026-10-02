package com.gte619n.healthfitness.shared.presentation.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.MedicationCrudRepository
import com.gte619n.healthfitness.shared.data.ReminderSettingsRepository
import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.domain.medications.MedicationReminderOverride
import com.gte619n.healthfitness.shared.domain.medications.MedicationStatus
import com.gte619n.healthfitness.shared.domain.medications.ReminderSettings
import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave B — port of the Android
 * `feature-medical/.../reminders/ReminderSettingsViewModel.kt` to KMP
 * `commonMain`, following the reference [MedicationsViewModel] shape (D2). The
 * master enable switch, per-window default times, and per-medication overrides
 * (mute + custom slot times) are all shared; only the DI wiring differs.
 *
 * Offline-first load (seed from cached settings + mirrored meds with no spinner,
 * then revalidate, keeping seeded values on failure) is preserved. On [save] the
 * settings doc is written (dropping no-op overrides so it stays minimal) and the
 * platform re-plans (D9 `LocalReminderScheduler.replan()`, supplied as [onReplan]),
 * mirroring Android's `engine.replan()`.
 */
data class ReminderSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
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

class ReminderSettingsViewModel(
    private val settingsRepo: ReminderSettingsRepository,
    private val medications: MedicationCrudRepository,
    private val onReplan: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(ReminderSettingsUiState())
    val state: StateFlow<ReminderSettingsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            // Offline-first: seed instantly from cached settings + mirrored meds so
            // the screen shows with no spinner on entry. The loader stays only when
            // nothing is cached (pre-first-sync). Then revalidate, keeping the seeded
            // values on failure.
            val cached = runCatching { settingsRepo.getCached() }.getOrNull()
            val cachedMeds = runCatching { medications.list(MedicationStatus.ACTIVE) }.getOrNull()
            if (cached != null) {
                _state.update {
                    it.copy(
                        loading = false,
                        enabled = cached.enabled,
                        windowTimes = cached.windowTimes,
                        perMedication = cached.perMedication,
                        medications = cachedMeds ?: it.medications,
                    )
                }
            }
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
                    if (it.loading) {
                        it.copy(loading = false, error = e.message ?: "Couldn't load reminder settings")
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = _state.update { it.copy(enabled = enabled) }

    fun setWindowTime(window: TimeWindow, time: String) =
        _state.update { it.copy(windowTimes = it.windowTimes + (window to time)) }

    fun setMedEnabled(medicationId: String, enabled: Boolean) = _state.update {
        val current = it.perMedication[medicationId] ?: MedicationReminderOverride()
        it.copy(perMedication = it.perMedication + (medicationId to current.copy(enabled = enabled)))
    }

    /** Pin one medication slot to a custom time; null clears back to the default. */
    fun setMedTime(medicationId: String, window: TimeWindow, time: String?) = _state.update {
        val current = it.perMedication[medicationId] ?: MedicationReminderOverride()
        val times = if (time == null) current.times - window else current.times + (window to time)
        it.copy(perMedication = it.perMedication + (medicationId to current.copy(times = times)))
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun save(onSaved: () -> Unit = {}) {
        val s = _state.value
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                settingsRepo.set(
                    ReminderSettings(
                        enabled = s.enabled,
                        windowTimes = s.windowTimes,
                        // Drop no-op overrides so the stored doc stays minimal.
                        perMedication = s.perMedication.filterValues { !it.enabled || it.times.isNotEmpty() },
                    ),
                )
                runCatching { onReplan() }
                _state.update { it.copy(saving = false, message = "Reminders updated") }
                onSaved()
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = e.message ?: "Couldn't save reminder settings") }
            }
        }
    }
}
