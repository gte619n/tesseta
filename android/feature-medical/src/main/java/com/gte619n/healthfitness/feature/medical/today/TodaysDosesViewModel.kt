package com.gte619n.healthfitness.feature.medical.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.medications.AdherenceRepository
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface TodaysDosesUiState {
    data object Loading : TodaysDosesUiState
    data class Ready(val doses: List<TodaysDose>) : TodaysDosesUiState
    data class Error(val message: String) : TodaysDosesUiState
}

@HiltViewModel
class TodaysDosesViewModel @Inject constructor(
    private val medications: MedicationRepository,
    private val adherence: AdherenceRepository,
    private val snackbar: SnackbarController,
) : ViewModel() {

    private val _state = MutableStateFlow<TodaysDosesUiState>(TodaysDosesUiState.Loading)
    val state: StateFlow<TodaysDosesUiState> = _state.asStateFlow()

    // Has a network revalidation resolved yet? Keeps the cold-open spinner up until
    // the first load settles, so we don't flash "no doses" before anything loaded.
    private var resolved = false

    init {
        observeDoses()
        refresh()
    }

    /**
     * State-management Phase 1: the card is driven by the single reactive source of
     * truth ([MedicationRepository.observeTodaysDoses]) — cached projection overlaid
     * with the adherence mirror. Any dose logged/undone anywhere (the reminder
     * notification's "Take all" / "✓" in a background receiver, an in-app toggle, or
     * a sync from another device) re-emits here and updates the card live, with no
     * ON_RESUME refresh or manual invalidation needed. This replaces the earlier
     * one-shot fetch that went stale when doses were taken off-screen.
     */
    private fun observeDoses() {
        viewModelScope.launch {
            medications.observeTodaysDoses().collect { doses ->
                // Don't leave the cold-open spinner for an empty projection until the
                // first revalidation has resolved; a real (non-empty) or post-resolve
                // emission always wins.
                if (doses.isNotEmpty() || resolved || _state.value is TodaysDosesUiState.Ready) {
                    _state.value = TodaysDosesUiState.Ready(doses)
                }
            }
        }
    }

    /** Background revalidation of the server projection; the reactive [state] shows the cached + mirror-overlaid list meanwhile. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { medications.refreshTodaysDoses() }
                .onFailure {
                    if (_state.value is TodaysDosesUiState.Loading) {
                        _state.value = TodaysDosesUiState.Error(it.message ?: "Could not load doses")
                    }
                }
            resolved = true
            // Leave the spinner even if the projection was genuinely empty.
            if (_state.value is TodaysDosesUiState.Loading) {
                _state.value = TodaysDosesUiState.Ready(emptyList())
            }
        }
    }

    /**
     * Toggle a dose. The adherence write is offline-first (optimistic Room mirror +
     * outbox), and the reactive [state] reflects it the instant the mirror changes —
     * so there's no manual optimistic flip to keep in sync. A rare local-write
     * failure just surfaces a snackbar; the reactive read remains the truth.
     */
    fun toggle(dose: TodaysDose) {
        viewModelScope.launch {
            runCatching {
                if (dose.taken) {
                    adherence.undoDose(dose.medicationId, LocalDate.now(), dose.window)
                } else {
                    adherence.logDose(dose.medicationId, dose.window)
                }
            }.onFailure { snackbar.showError("Could not save — try again") }
        }
    }
}
