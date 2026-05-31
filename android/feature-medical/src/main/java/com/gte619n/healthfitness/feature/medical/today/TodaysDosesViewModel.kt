package com.gte619n.healthfitness.feature.medical.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.medications.AdherenceRepository
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val snackbar: SnackbarController,
) : ViewModel() {

    private val _state = MutableStateFlow<TodaysDosesUiState>(TodaysDosesUiState.Loading)
    val state: StateFlow<TodaysDosesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { medications.todaysDoses() }
                .onSuccess { _state.value = TodaysDosesUiState.Ready(it) }
                .onFailure {
                    _state.value = TodaysDosesUiState.Error(it.message ?: "Could not load doses")
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
