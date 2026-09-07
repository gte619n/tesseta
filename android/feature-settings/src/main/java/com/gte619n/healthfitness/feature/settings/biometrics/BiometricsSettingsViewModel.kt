package com.gte619n.healthfitness.feature.settings.biometrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.biometrics.BiometricsRepository
import com.gte619n.healthfitness.data.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.biometrics.BiometricSummary
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BiometricsSettingsViewModel @Inject constructor(
    private val repo: BiometricsRepository,
    unitPrefs: UnitPreferencesRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Loaded(val items: List<BiometricSummary>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    val weightUnit: StateFlow<WeightUnit> = unitPrefs.preferences
        .map { it.weight }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.POUNDS)

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            repo.list().fold(
                onSuccess = { _state.value = UiState.Loaded(it) },
                onFailure = { _state.value = UiState.Error(it.message ?: "Couldn't load biometrics") },
            )
        }
    }

    fun toggle(key: String) {
        val current = _state.value as? UiState.Loaded ?: return
        // Optimistically flip the switch, then persist the full hidden set.
        val next = current.items.map { if (it.key == key) it.copy(visible = !it.visible) else it }
        _state.value = UiState.Loaded(next)
        val hidden = next.filter { !it.visible }.map { it.key }
        viewModelScope.launch {
            repo.setVisibility(hidden).fold(
                onSuccess = { _state.value = UiState.Loaded(it) },
                onFailure = { _state.value = UiState.Loaded(current.items) }, // revert
            )
        }
    }
}
