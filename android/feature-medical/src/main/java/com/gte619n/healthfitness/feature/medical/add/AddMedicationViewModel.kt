package com.gte619n.healthfitness.feature.medical.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.DrugRepository
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Add-medication flow:
 *
 *   SEARCH step: user types a query, we filter the local catalog. When
 *   the query is at least three chars and produces no exact match, after
 *   a 400ms debounce we kick off the SSE drug lookup. Phase events
 *   surface in [AddMedicationUiState.lookupEvent]; a Found event flips
 *   the step to FORM. A NotFound event surfaces the "Add manually"
 *   affordance (which flips the step to CUSTOM).
 *
 *   FORM step: dose / unit / frequency / time slots / prescribedBy /
 *   notes — submit POSTs to /api/me/medications.
 *
 *   CUSTOM step: same form, no drugId, customName + customForm filled in.
 */
@HiltViewModel
class AddMedicationViewModel @Inject constructor(
    private val drugs: DrugRepository,
    private val medications: MedicationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddMedicationUiState())
    val state: StateFlow<AddMedicationUiState> = _state.asStateFlow()

    private var lookupJob: Job? = null
    private var catalogJob: Job? = null

    init { loadCatalog() }

    private fun loadCatalog() {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            runCatching { drugs.catalog() }
                .onSuccess { catalog ->
                    _state.update { it.copy(catalog = catalog) }
                }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, lookupEvent = null) }
        val q = query.trim()
        val matches = _state.value.catalog.filter { it.name.contains(q, ignoreCase = true) }
        _state.update { it.copy(filtered = matches) }

        // Cancel any in-flight SSE; let the user keep typing before we
        // re-fire. If the query gets short or maps onto an existing
        // catalog entry, we just don't start a new stream.
        lookupJob?.cancel()
        if (q.length < 3) return
        val exactMatch = matches.any { it.name.equals(q, ignoreCase = true) }
        if (exactMatch) return
        lookupJob = viewModelScope.launch {
            delay(400)
            drugs.lookupStream(q).collect { event ->
                _state.update { it.copy(lookupEvent = event) }
                if (event is DrugLookupEvent.Found) {
                    selectDrug(event.drug)
                }
            }
        }
    }

    fun selectDrug(drug: Drug) {
        _state.update {
            it.copy(
                selectedDrug = drug,
                step = AddMedicationStep.Form,
                unit = drug.defaultUnit,
                lookupEvent = null,
            )
        }
    }

    fun chooseManualEntry() {
        _state.update {
            it.copy(
                step = AddMedicationStep.Custom,
                lookupEvent = null,
            )
        }
    }

    fun onDoseChange(dose: Double) {
        _state.update { it.copy(dose = dose) }
    }

    fun onUnitChange(unit: String) {
        _state.update { it.copy(unit = unit) }
    }

    fun onFrequencyChange(config: com.gte619n.healthfitness.domain.medications.FrequencyConfig) {
        _state.update { it.copy(frequency = config) }
    }

    fun onTimeSlotsChange(slots: List<com.gte619n.healthfitness.domain.medications.TimeSlot>) {
        _state.update { it.copy(timeSlots = slots) }
    }

    fun onPrescribedByChange(value: String) {
        _state.update { it.copy(prescribedBy = value) }
    }

    fun onNotesChange(value: String) {
        _state.update { it.copy(notes = value) }
    }

    fun onCustomNameChange(value: String) {
        _state.update { it.copy(customName = value) }
    }

    fun submit(onDone: (Medication) -> Unit) {
        val s = _state.value
        val request = CreateMedicationRequest(
            drugId = s.selectedDrug?.drugId,
            customName = if (s.step == AddMedicationStep.Custom) s.customName else null,
            dose = s.dose,
            unit = s.unit,
            frequency = s.frequency,
            timeSlots = s.timeSlots,
            notes = s.notes.ifBlank { null },
            prescribedBy = s.prescribedBy.ifBlank { null },
        )
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            runCatching { medications.create(request) }
                .onSuccess { med ->
                    _state.update { it.copy(isSubmitting = false) }
                    onDone(med)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isSubmitting = false, error = e.message ?: "Could not save")
                    }
                }
        }
    }
}

enum class AddMedicationStep { Search, Form, Custom }

data class AddMedicationUiState(
    val step: AddMedicationStep = AddMedicationStep.Search,
    val query: String = "",
    val catalog: List<Drug> = emptyList(),
    val filtered: List<Drug> = emptyList(),
    val selectedDrug: Drug? = null,
    val lookupEvent: DrugLookupEvent? = null,

    // Form fields
    val dose: Double = 0.0,
    val unit: String = "mg",
    val frequency: com.gte619n.healthfitness.domain.medications.FrequencyConfig =
        com.gte619n.healthfitness.domain.medications.FrequencyConfig(
            type = com.gte619n.healthfitness.domain.medications.FrequencyType.DAILY,
            timesPerPeriod = 1,
        ),
    val timeSlots: List<com.gte619n.healthfitness.domain.medications.TimeSlot> = emptyList(),
    val prescribedBy: String = "",
    val notes: String = "",
    val customName: String = "",

    val isSubmitting: Boolean = false,
    val error: String? = null,
)
