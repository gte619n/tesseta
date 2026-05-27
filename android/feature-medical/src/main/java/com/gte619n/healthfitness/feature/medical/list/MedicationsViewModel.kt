package com.gte619n.healthfitness.feature.medical.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads the user's medications (both ACTIVE and DISCONTINUED) and splits
 * them into the two tabs. A single GET — list endpoint returns all by
 * default; partitioning happens on the client so the user can switch
 * tabs without a network round-trip.
 */
@HiltViewModel
class MedicationsViewModel @Inject constructor(
    private val medications: MedicationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<MedicationsUiState>(MedicationsUiState.Loading)
    val state: StateFlow<MedicationsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = MedicationsUiState.Loading
            runCatching { medications.list() }
                .onSuccess { all ->
                    _state.value = MedicationsUiState.Ready(
                        active = all.filter { it.status == MedicationStatus.ACTIVE },
                        discontinued = all.filter { it.status == MedicationStatus.DISCONTINUED },
                    )
                }
                .onFailure { e ->
                    _state.value = MedicationsUiState.Error(e.message ?: "Unknown error")
                }
        }
    }
}

sealed interface MedicationsUiState {
    data object Loading : MedicationsUiState
    data class Ready(
        val active: List<Medication>,
        val discontinued: List<Medication>,
    ) : MedicationsUiState
    data class Error(val message: String) : MedicationsUiState
}
