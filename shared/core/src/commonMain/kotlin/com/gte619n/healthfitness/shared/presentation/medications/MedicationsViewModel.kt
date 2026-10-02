package com.gte619n.healthfitness.shared.presentation.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.MedicationRepository
import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.domain.medications.MedicationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 1D — REFERENCE shared ViewModel. Port of the Android
 * `feature-medical/.../list/MedicationsViewModel.kt` to KMP `commonMain`:
 *
 *  - `androidx.lifecycle.ViewModel` (KMP artifact, D2) instead of Hilt's
 *    `@HiltViewModel` — the repository is a plain constructor param wired by the
 *    platform DI (Hilt on Android, a small factory on iOS).
 *  - The UI-state `sealed interface` + reactive `StateFlow` off the mirror are
 *    IDENTICAL to the Android original — the offline-first "no Loading reset on
 *    re-entry" behavior is shared, not reimplemented per platform.
 *  - SKIE exposes `state` to Swift as an `AsyncSequence`; the SwiftUI
 *    `@Observable` bridge (ios/HealthFitness/Presentation/ObservableBridge.swift)
 *    subscribes and republishes. See MedicationsListView.swift for the pattern.
 *
 * Every Phase 3 feature vertical replicates this shape: sealed UiState + one
 * StateFlow per screen + suspend intents.
 */
sealed interface MedicationsUiState {
    data object Loading : MedicationsUiState
    data class Ready(
        val active: List<Medication>,
        val discontinued: List<Medication>,
    ) : MedicationsUiState
    data class Error(val message: String) : MedicationsUiState
}

enum class MedicationsTab(val label: String) { CURRENT("Current"), HISTORY("History") }

class MedicationsViewModel(
    private val medications: MedicationRepository,
) : ViewModel() {

    val state: StateFlow<MedicationsUiState> =
        medications.observe()
            .map { meds ->
                MedicationsUiState.Ready(
                    active = meds.filter { it.status == MedicationStatus.ACTIVE },
                    discontinued = meds.filter { it.status == MedicationStatus.DISCONTINUED },
                ) as MedicationsUiState
            }
            .catch { emit(MedicationsUiState.Error(it.message ?: "Could not load medications")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MedicationsUiState.Loading,
            )

    private val _tab = MutableStateFlow(MedicationsTab.CURRENT)
    val tab: StateFlow<MedicationsTab> = _tab.asStateFlow()

    fun setTab(tab: MedicationsTab) { _tab.value = tab }

    /** Best-effort revalidation; never flips [state] back to Loading. */
    fun refresh() {
        viewModelScope.launch { runCatching { medications.refresh() } }
    }
}
