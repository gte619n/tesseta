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

data class AddMedicationUiState(
    val step: Step = Step.SEARCH,
    val query: String = "",
    val catalog: List<Drug> = emptyList(),
    val filteredCatalog: List<Drug> = emptyList(),
    val selectedDrug: Drug? = null,
    val lookupEvent: DrugLookupEvent? = null,
    val isLooking: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    enum class Step { SEARCH, FORM, CUSTOM }
}

@HiltViewModel
class AddMedicationViewModel @Inject constructor(
    private val drugs: DrugRepository,
    private val medications: MedicationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddMedicationUiState())
    val state: StateFlow<AddMedicationUiState> = _state.asStateFlow()

    private var lookupJob: Job? = null
    private var debounceJob: Job? = null

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            runCatching { drugs.catalog() }
                .onSuccess { catalog -> _state.update { it.copy(catalog = catalog) } }
            // Catalog failure is non-fatal; SSE lookup still works.
        }
    }

    fun onQueryChange(query: String) {
        val matches = filterCatalog(query)
        _state.update {
            it.copy(query = query, filteredCatalog = matches, lookupEvent = null)
        }

        // Cancel any in-flight lookup whenever the query changes.
        lookupJob?.cancel()
        debounceJob?.cancel()
        _state.update { it.copy(isLooking = false) }

        // Auto-trigger SSE lookup only when there's no local match and the
        // query is meaningful (>= 3 chars), after a short debounce.
        if (matches.isEmpty() && query.trim().length >= 3) {
            debounceJob = viewModelScope.launch {
                delay(400)
                startLookup(query.trim())
            }
        }
    }

    private fun filterCatalog(query: String): List<Drug> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return _state.value.catalog.filter { drug ->
            drug.name.contains(q, ignoreCase = true) ||
                drug.aliases.any { it.contains(q, ignoreCase = true) }
        }
    }

    fun startLookup(query: String) {
        lookupJob?.cancel()
        _state.update { it.copy(isLooking = true, lookupEvent = null) }
        lookupJob = viewModelScope.launch {
            runCatching {
                drugs.lookupStream(query).collect { event ->
                    _state.update { it.copy(lookupEvent = event) }
                    when (event) {
                        is DrugLookupEvent.Found -> {
                            _state.update {
                                it.copy(
                                    selectedDrug = event.drug,
                                    step = AddMedicationUiState.Step.FORM,
                                    isLooking = false,
                                )
                            }
                        }
                        is DrugLookupEvent.NotFound -> _state.update { it.copy(isLooking = false) }
                        is DrugLookupEvent.Failed -> _state.update { it.copy(isLooking = false) }
                        is DrugLookupEvent.Progress -> Unit
                    }
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        isLooking = false,
                        lookupEvent = DrugLookupEvent.Failed(e.message ?: "Lookup failed"),
                    )
                }
            }
        }
    }

    /** Pick a catalog/AI drug and advance to the dose form. */
    fun selectDrug(drug: Drug) {
        lookupJob?.cancel()
        debounceJob?.cancel()
        _state.update {
            it.copy(
                selectedDrug = drug,
                step = AddMedicationUiState.Step.FORM,
                isLooking = false,
            )
        }
    }

    /** Switch to the manual custom-entry form. */
    fun startManualEntry() {
        lookupJob?.cancel()
        debounceJob?.cancel()
        _state.update {
            it.copy(
                selectedDrug = null,
                step = AddMedicationUiState.Step.CUSTOM,
                isLooking = false,
            )
        }
    }

    fun backToSearch() {
        _state.update {
            it.copy(step = AddMedicationUiState.Step.SEARCH, selectedDrug = null, error = null)
        }
    }

    fun submit(request: CreateMedicationRequest, onDone: (Medication) -> Unit) {
        if (_state.value.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            runCatching { medications.create(request) }
                .onSuccess { created ->
                    _state.update { it.copy(isSubmitting = false) }
                    onDone(created)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isSubmitting = false, error = e.message ?: "Could not save medication")
                    }
                }
        }
    }
}
