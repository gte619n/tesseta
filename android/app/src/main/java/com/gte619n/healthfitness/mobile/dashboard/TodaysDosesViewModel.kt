package com.gte619n.healthfitness.mobile.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.medications.AdherenceRepository
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Backs the interactive Today's Doses card on the dashboard. Replaces
 * the IMPL-AND-01 stub which flowed through DashboardViewModel.
 *
 * Optimistic UX: tapping a row's checkbox flips [TodaysDose.taken]
 * locally first, fires the POST / DELETE in the background, and on
 * failure re-fetches the truth (the backend handles "already logged"
 * idempotently — duplicate POSTs replace the existing dose for that
 * window).
 *
 * The dashboard's wider DashboardViewModel still owns the body-comp +
 * blood panels; this VM is scoped to a single composable so its state
 * tracks independently and the user doesn't lose checkbox flips when
 * the rest of the dashboard refreshes.
 */
@HiltViewModel
class TodaysDosesViewModel @Inject constructor(
    private val medications: MedicationRepository,
    private val adherence: AdherenceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TodaysDosesUiState>(TodaysDosesUiState.Loading)
    val state: StateFlow<TodaysDosesUiState> = _state.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { medications.todaysDoses() }
                .onSuccess { _state.value = TodaysDosesUiState.Ready(it) }
                .onFailure { e ->
                    _state.value = TodaysDosesUiState.Error(e.message ?: "Unknown error")
                }
        }
    }

    fun clearError() {
        _errors.value = null
    }

    fun toggle(dose: TodaysDose) {
        // Optimistic flip
        _state.update { s ->
            if (s !is TodaysDosesUiState.Ready) s
            else s.copy(
                doses = s.doses.map {
                    if (it.medicationId == dose.medicationId && it.window == dose.window)
                        it.copy(taken = !it.taken)
                    else it
                },
            )
        }
        viewModelScope.launch {
            runCatching {
                if (dose.taken) {
                    adherence.undoDose(dose.medicationId, LocalDate.now(), dose.window)
                } else {
                    adherence.logDose(dose.medicationId, dose.window)
                }
            }.onFailure {
                _errors.value = "Couldn't save — try again"
                refresh()      // revert by re-fetching truth
            }
        }
    }
}

sealed interface TodaysDosesUiState {
    data object Loading : TodaysDosesUiState
    data class Ready(val doses: List<TodaysDose>) : TodaysDosesUiState
    data class Error(val message: String) : TodaysDosesUiState

    /** Helper for the "no scheduled doses today" empty state. */
    val isEmpty: Boolean
        get() = this is Ready && doses.isEmpty()
}

/** Maps the medications-domain TimeWindow to the dashboard-domain DoseWindow short caps. */
internal fun TimeWindow.shortCaps(): String = when (this) {
    TimeWindow.MORNING -> "AM"
    TimeWindow.AFTERNOON -> "NOON"
    TimeWindow.EVENING -> "PM"
    TimeWindow.BEDTIME -> "BED"
}
