package com.gte619n.healthfitness.feature.medical.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MedicationsUiState {
    data object Loading : MedicationsUiState
    data class Ready(
        val active: List<Medication>,
        val discontinued: List<Medication>,
    ) : MedicationsUiState
    data class Error(val message: String) : MedicationsUiState
}

enum class MedicationsTab(val label: String) {
    CURRENT("Current"),
    HISTORY("History"),
}

@HiltViewModel
class MedicationsViewModel @Inject constructor(
    private val medications: MedicationRepository,
) : ViewModel() {

    // offline-fix: the list is reactive off the Room mirror, so the screen shows the
    // last-synced medications INSTANTLY on every entry — no `Loading` reset, no
    // network wait — and updates in place as optimistic writes / sync deltas land.
    // `Loading` is only the brief initial value before the first mirror emission
    // (i.e. the very first sync); a warm mirror emits before the first frame.
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

    fun setTab(tab: MedicationsTab) {
        _tab.value = tab
    }

    /**
     * offline-fix: revalidate the mirror from the network (best-effort). The
     * reactive [state] reflects the result; this never flips the UI back to
     * `Loading`. Safe to call on every screen resume.
     */
    fun refresh() {
        viewModelScope.launch { runCatching { medications.refresh() } }
    }
}
