package com.gte619n.healthfitness.shared.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.ProfileRepository
import com.gte619n.healthfitness.shared.data.UnitPreferencesRepository
import com.gte619n.healthfitness.shared.domain.prefs.HeightUnit
import com.gte619n.healthfitness.shared.domain.profile.HeightMetric
import com.gte619n.healthfitness.shared.domain.profile.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — shared port of the Android
 * `feature-settings/.../profile/ProfileViewModel.kt`. Field names, the sealed
 * [UiState], and the offline-first refresh logic are IDENTICAL to Android — the
 * "seed from the mirror instantly, never bounce back to Loading once loaded"
 * behavior is shared, not reimplemented per platform.
 */
class ProfileViewModel(
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
        viewModelScope.launch {
            // Offline-first: seed instantly from the Room mirror so re-entry shows
            // the profile with no spinner. Only stay on Loading when nothing is
            // cached yet (pre-first-sync). Then revalidate via the network.
            val cached = runCatching { repo.cached() }.getOrNull()
            if (cached != null) _state.value = UiState.Loaded(cached)
            repo.get().fold(
                onSuccess = { _state.value = UiState.Loaded(it) },
                onFailure = {
                    // Keep any seeded profile on screen; only surface an error when
                    // there's nothing to show.
                    if (_state.value !is UiState.Loaded) {
                        _state.value = UiState.Error(it.message ?: "Failed to load profile")
                    }
                },
            )
        }
    }

    fun saveHeight(feet: Int, inches: Int) {
        saveHeightCm(HeightMetric.ftInToCm(feet, inches))
    }

    fun saveHeightCm(heightCm: Int) {
        save("height") { repo.updateHeightCm(heightCm) }
    }

    /** biologicalSex = "MALE" | "FEMALE" | null (feeds the calorie estimate). */
    fun saveBiologicalSex(biologicalSex: String?) {
        save("biological sex") { repo.updateBiologicalSex(biologicalSex) }
    }

    /** dateOfBirth = ISO "YYYY-MM-DD" | null (feeds the calorie estimate). */
    fun saveDateOfBirth(dateOfBirth: String?) {
        save("date of birth") { repo.updateDateOfBirth(dateOfBirth) }
    }

    private fun save(label: String, block: suspend () -> Result<Profile>) {
        val current = _state.value
        if (current !is UiState.Loaded) return
        _state.value = current.copy(saving = true)
        viewModelScope.launch {
            block().fold(
                onSuccess = { _state.value = UiState.Loaded(it) },
                onFailure = {
                    _state.value = UiState.Error(it.message ?: "Failed to save $label")
                },
            )
        }
    }
}
