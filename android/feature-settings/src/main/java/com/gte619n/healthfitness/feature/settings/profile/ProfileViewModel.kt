package com.gte619n.healthfitness.feature.settings.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.profile.HeightMetric
import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.domain.profile.ProfileRepository
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
class ProfileViewModel @Inject constructor(
    private val repo: ProfileRepository,
    unitPrefs: UnitPreferencesRepository,
) : ViewModel() {

    /** Drives whether the height editor shows ft/in or a single cm field. */
    val heightUnit: StateFlow<HeightUnit> = unitPrefs.preferences
        .map { it.height }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeightUnit.FEET_INCHES)

    sealed interface UiState {
        data object Loading : UiState
        data class Loaded(val profile: Profile, val saving: Boolean = false) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            repo.get().fold(
                onSuccess = { _state.value = UiState.Loaded(it) },
                onFailure = {
                    _state.value = UiState.Error(it.message ?: "Failed to load profile")
                },
            )
        }
    }

    fun saveHeight(feet: Int, inches: Int) {
        saveHeightCm(HeightMetric.ftInToCm(feet, inches))
    }

    fun saveHeightCm(heightCm: Int) {
        val current = _state.value
        if (current !is UiState.Loaded) return
        _state.value = current.copy(saving = true)
        viewModelScope.launch {
            repo.updateHeightCm(heightCm).fold(
                onSuccess = { _state.value = UiState.Loaded(it) },
                onFailure = {
                    _state.value = UiState.Error(it.message ?: "Failed to save height")
                },
            )
        }
    }
}
