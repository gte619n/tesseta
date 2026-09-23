package com.gte619n.healthfitness.shared.presentation.blood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.BloodReadingRepository
import com.gte619n.healthfitness.shared.domain.blood.BloodMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — shared "add a manual reading" form VM. Port of
 * android/feature-blood/.../AddReadingViewModel (Hilt → plain constructor,
 * java.time → kotlinx-datetime). A single [FormState] StateFlow + suspend submit
 * intent; the optimistic outbox create lives in the repository.
 */
class AddReadingViewModel(
    private val readings: BloodReadingRepository,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    data class FormState(
        val marker: BloodMarker? = null,
        val value: String = "",
        val unit: String = "",
        val sampleDate: LocalDate,
        val labSource: String = "",
        val notes: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
    ) {
        val canSubmit: Boolean
            get() = marker != null && value.toDoubleOrNull() != null && !submitting
    }

    private val _form = MutableStateFlow(
        FormState(sampleDate = clock.todayIn(TimeZone.currentSystemDefault())),
    )
    val form: StateFlow<FormState> = _form.asStateFlow()

    fun onMarker(m: BloodMarker) = _form.update { it.copy(marker = m, error = null) }
    fun onValue(s: String) = _form.update { it.copy(value = s, error = null) }
    fun onUnit(s: String) = _form.update { it.copy(unit = s) }
    fun onDate(d: LocalDate) = _form.update { it.copy(sampleDate = d) }
    fun onLabSource(s: String) = _form.update { it.copy(labSource = s) }
    fun onNotes(s: String) = _form.update { it.copy(notes = s) }

    fun submit(onSuccess: () -> Unit) {
        val current = _form.value
        val marker = current.marker
        val value = current.value.toDoubleOrNull()
        if (marker == null || value == null) {
            _form.update { it.copy(error = "Pick a marker and enter a numeric value") }
            return
        }
        _form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                readings.create(
                    marker = marker,
                    value = value,
                    unit = current.unit.takeIf { it.isNotBlank() },
                    sampleDate = current.sampleDate,
                    labSource = current.labSource.takeIf { it.isNotBlank() },
                    notes = current.notes.takeIf { it.isNotBlank() },
                )
            }.onSuccess {
                _form.update { it.copy(submitting = false) }
                onSuccess()
            }.onFailure { e ->
                _form.update {
                    it.copy(submitting = false, error = e.message ?: "Failed to save reading")
                }
            }
        }
    }
}
