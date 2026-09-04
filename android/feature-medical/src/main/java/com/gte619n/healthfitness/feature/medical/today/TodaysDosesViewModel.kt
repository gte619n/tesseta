package com.gte619n.healthfitness.feature.medical.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.medications.AdherenceRepository
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.data.sync.LocalWriteBus
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
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
    private val localWriteBus: LocalWriteBus,
    private val snackbar: SnackbarController,
) : ViewModel() {

    private val _state = MutableStateFlow<TodaysDosesUiState>(TodaysDosesUiState.Loading)
    val state: StateFlow<TodaysDosesUiState> = _state.asStateFlow()

    init {
        refresh()
        observeAdherenceWrites()
    }

    /**
     * Re-read whenever a dose is logged/undone (or a medication changes) ANYWHERE
     * — the reminder notification's "Take all" / "✓" actions run in a background
     * receiver and write the adherence mirror while this screen is already
     * resumed, so an ON_RESUME-only refresh never saw them and the card went
     * stale. The mirror (overlaid by [MedicationRepository.todaysDoses]) is the
     * source of truth; this just makes the card observe writes to it, matching the
     * dashboard's LocalWriteBus invalidation.
     */
    private fun observeAdherenceWrites() {
        viewModelScope.launch {
            localWriteBus.writes
                .filter { it == MirrorTables.MEDICATION_ADHERENCE || it == MirrorTables.MEDICATIONS }
                .debounce(250)
                .collect { refresh() }
        }
    }

    /**
     * offline-fix: stale-while-revalidate, matching the dashboard card. Seed from
     * the cache first (instant, no spinner) THEN revalidate from the network — both
     * in one coroutine so the fresh result always wins the race and can't be clobbered
     * by a late cache seed. Only shows a spinner on a cold first open (empty cache),
     * and only surfaces an error when there's nothing already on screen.
     */
    fun refresh() {
        viewModelScope.launch {
            if (_state.value !is TodaysDosesUiState.Ready) {
                runCatching { medications.cachedTodaysDoses() }.getOrNull()?.let {
                    _state.value = TodaysDosesUiState.Ready(it)
                }
            }
            runCatching { medications.todaysDoses() }
                .onSuccess { _state.value = TodaysDosesUiState.Ready(it) }
                .onFailure {
                    if (_state.value !is TodaysDosesUiState.Ready) {
                        _state.value = TodaysDosesUiState.Error(it.message ?: "Could not load doses")
                    }
                }
        }
    }

    /** Optimistic toggle: flip the row immediately, fire the call, revert on failure. */
    fun toggle(dose: TodaysDose) {
        // Optimistic update.
        _state.update { s ->
            if (s !is TodaysDosesUiState.Ready) {
                s
            } else {
                s.copy(
                    doses = s.doses.map {
                        if (it.medicationId == dose.medicationId && it.window == dose.window) {
                            it.copy(taken = !it.taken)
                        } else {
                            it
                        }
                    },
                )
            }
        }
        viewModelScope.launch {
            runCatching {
                if (dose.taken) {
                    adherence.undoDose(dose.medicationId, LocalDate.now(), dose.window)
                } else {
                    adherence.logDose(dose.medicationId, dose.window)
                }
            }.onFailure {
                snackbar.showError("Could not save — try again")
                refresh() // revert by re-fetching truth
            }
        }
    }
}
