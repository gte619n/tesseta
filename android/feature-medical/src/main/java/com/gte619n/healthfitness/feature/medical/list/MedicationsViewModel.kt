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

    private val _state = MutableStateFlow<MedicationsUiState>(MedicationsUiState.Loading)
    val state: StateFlow<MedicationsUiState> = _state.asStateFlow()

    private val _tab = MutableStateFlow(MedicationsTab.CURRENT)
    val tab: StateFlow<MedicationsTab> = _tab.asStateFlow()

    fun setTab(tab: MedicationsTab) {
        _tab.value = tab
    }

    fun refresh() {
        _state.value = MedicationsUiState.Loading
        viewModelScope.launch {
            runCatching { medications.list() }
                .onSuccess { all ->
                    _state.value = MedicationsUiState.Ready(
                        active = all.filter { it.status == MedicationStatus.ACTIVE },
                        discontinued = all.filter { it.status == MedicationStatus.DISCONTINUED },
                    )
                }
                .onFailure {
                    _state.value = MedicationsUiState.Error(it.message ?: "Could not load medications")
                }
        }
    }
}
