package com.gte619n.healthfitness.feature.settings.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.prefs.CoachAudioPreferences
import com.gte619n.healthfitness.domain.prefs.CoachAudioSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoachAudioSettingsViewModel @Inject constructor(
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
