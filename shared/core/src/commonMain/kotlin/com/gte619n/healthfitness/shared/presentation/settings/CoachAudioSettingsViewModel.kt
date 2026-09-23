package com.gte619n.healthfitness.shared.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.CoachAudioPreferences
import com.gte619n.healthfitness.shared.domain.prefs.CoachAudioSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — shared port of the Android
 * `feature-settings/.../coach/CoachAudioSettingsViewModel.kt`. On-device
 * workout-coach audio toggles (rest beep / voice announcements). Identical to
 * Android.
 */
class CoachAudioSettingsViewModel(
    private val preferences: CoachAudioPreferences,
) : ViewModel() {

    val settings: StateFlow<CoachAudioSettings> =
        preferences.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CoachAudioSettings(),
        )

    fun setRestBeep(enabled: Boolean) {
        viewModelScope.launch { preferences.setRestBeep(enabled) }
    }

    fun setVoiceAnnouncements(enabled: Boolean) {
        viewModelScope.launch { preferences.setVoiceAnnouncements(enabled) }
    }
}
