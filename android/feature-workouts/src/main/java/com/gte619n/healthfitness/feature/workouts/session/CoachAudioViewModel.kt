package com.gte619n.healthfitness.feature.workouts.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.prefs.CoachAudioPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Exposes the coach's voice-announcement toggle to the session route. Kept
 * separate from [WorkoutSessionViewModel] so the logger's draft/timer logic (and
 * its tests) stay independent of audio prefs. The rest-end beep is handled in
 * the foreground service; this only gates the spoken set cues.
 */
@HiltViewModel
class CoachAudioViewModel @Inject constructor(
    preferences: CoachAudioPreferences,
) : ViewModel() {

    val voiceAnnouncements: StateFlow<Boolean> =
        preferences.settings
            .map { it.voiceAnnouncements }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
}
