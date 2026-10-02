package com.gte619n.healthfitness.shared.presentation.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.DrugRepository
import com.gte619n.healthfitness.shared.data.MedicationCrudRepository
import com.gte619n.healthfitness.shared.data.ReminderSettingsRepository
import com.gte619n.healthfitness.shared.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.shared.domain.medications.Drug
import com.gte619n.healthfitness.shared.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.shared.domain.medications.InlineReminderConfig
import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.domain.medications.MedicationReminderOverride
import com.gte619n.healthfitness.shared.domain.medications.ReminderSettings
import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave B — port of the Android
 * `feature-medical/.../add/AddMedicationViewModel.kt` to KMP `commonMain`,
 * following the reference [MedicationsViewModel] shape (D2). The three-step
 * add flow (catalog SEARCH → dose FORM → manual CUSTOM), the debounced online-only
 * AI SSE lookup, and the inline-reminder persist-on-submit are all shared; only
 * the DI wiring differs (Hilt on Android, a small factory on iOS).
 *
 * D9 anti-drift: after a successful create the inline reminder override is merged
 * onto the shared [ReminderSettings] doc keyed by the new medication id (only a
 * non-default override is stored). This is the INPUT the D9
 * `LocalReminderScheduler` re-reads through the shared `ReminderPlanner` /
 * `OutstandingDoses` to (re)plan notifications — the platform does not re-derive
 * dose times. Re-planning is triggered by the platform on the create result
 * (iOS: `LocalReminderScheduler.replan()`), mirroring Android's `reminderEngine
 * .replan()`; there is no shared reminder engine, so the VM just persists.
 */
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
    /** Global default window times, for the inline reminder controls' fallback. */
    val globalWindowTimes: Map<TimeWindow, String> = ReminderSettings.DEFAULT_WINDOW_TIMES,
) {
    enum class Step { SEARCH, FORM, CUSTOM }
}

class AddMedicationViewModel(
    private val drugs: DrugRepository,
    private val medications: MedicationCrudRepository,
    private val reminderSettings: ReminderSettingsRepository,
    /** Online gate for the AI lookup (D17 #41) — supplied by platform connectivity. */
    private val isOnline: StateFlow<Boolean>,
    /**
     * Platform re-plan hook, invoked after a create so the D9 scheduler picks up
     * the new medication. No-op default keeps the VM unit-testable without a
     * scheduler (mirrors Android's `reminderEngine.replan()`, best-effort).
     */
    private val onReplan: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(AddMedicationUiState())
    val state: StateFlow<AddMedicationUiState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = isOnline

    private var lookupJob: Job? = null
    private var debounceJob: Job? = null

    init {
        loadCatalog()
        loadReminderDefaults()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            runCatching { drugs.catalog() }
                .onSuccess { catalog -> _state.update { it.copy(catalog = catalog) } }
            // Catalog failure is non-fatal; SSE lookup still works.
        }
    }

    /** Cached-only read of the global window times for the inline picker default. */
    private fun loadReminderDefaults() {
        viewModelScope.launch {
            runCatching { reminderSettings.getCached() }
                .onSuccess { s -> _state.update { it.copy(globalWindowTimes = s.windowTimes) } }
        }
    }

    fun onQueryChange(query: String) {
        val matches = filterCatalog(query)
        _state.update { it.copy(query = query, filteredCatalog = matches, lookupEvent = null) }

        lookupJob?.cancel()
        debounceJob?.cancel()
        _state.update { it.copy(isLooking = false) }

        // Auto-trigger SSE lookup only with no local match, a meaningful query
        // (>= 3 chars), and online (D17 #41 — never fire the AI lookup offline).
        if (matches.isEmpty() && query.trim().length >= 3 && isOnline.value) {
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
                        is DrugLookupEvent.Found -> _state.update {
                            it.copy(
                                selectedDrug = event.drug,
                                step = AddMedicationUiState.Step.FORM,
                                isLooking = false,
                            )
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
            it.copy(selectedDrug = drug, step = AddMedicationUiState.Step.FORM, isLooking = false)
        }
    }

    /** Switch to the manual custom-entry form. */
    fun startManualEntry() {
        lookupJob?.cancel()
        debounceJob?.cancel()
        _state.update {
            it.copy(selectedDrug = null, step = AddMedicationUiState.Step.CUSTOM, isLooking = false)
        }
    }

    fun backToSearch() {
        _state.update {
            it.copy(step = AddMedicationUiState.Step.SEARCH, selectedDrug = null, error = null)
        }
    }

    fun submit(
        request: CreateMedicationRequest,
        reminder: InlineReminderConfig = InlineReminderConfig(),
        onDone: (Medication) -> Unit,
    ) {
        if (_state.value.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            runCatching { medications.create(request) }
                .onSuccess { created ->
                    persistReminderOverride(created.medicationId, reminder)
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

    /**
     * Merge the inline reminder config onto the shared [ReminderSettings] doc keyed
     * by the new medication id. Only a non-default override (muted, or any pinned
     * time) is stored, so an all-default med keeps the doc minimal. Best-effort +
     * offline-safe: the med is already created, so a failed settings write must not
     * surface as a save error — the med still reminds at the global default. Always
     * re-plan so the D9 scheduler picks up the new med.
     */
    private suspend fun persistReminderOverride(medicationId: String, reminder: InlineReminderConfig) {
        val isDefault = reminder.enabled && reminder.times.isEmpty()
        if (!isDefault) {
            runCatching {
                val current = reminderSettings.getCached()
                reminderSettings.set(
                    current.copy(
                        perMedication = current.perMedication + (
                            medicationId to MedicationReminderOverride(
                                enabled = reminder.enabled,
                                times = reminder.times,
                            )
                        ),
                    ),
                )
            }
        }
        runCatching { onReplan() }
    }
}
