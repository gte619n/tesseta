package com.gte619n.healthfitness.shared.data.ios

import com.gte619n.healthfitness.shared.data.CoachAudioPreferences
import com.gte619n.healthfitness.shared.domain.prefs.CoachAudioSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * IMPL-IOS-01 Phase 1C — iOS [CoachAudioPreferences] (on-device, NSUserDefaults),
 * the workout-coach audio toggles. Both default ON (absent key = true) to match
 * Android's DataStore defaults. Same reactive MutableStateFlow pattern as the
 * unit-preferences store.
 */
class NSUserDefaultsCoachAudioPreferences(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : CoachAudioPreferences {

    private val state = MutableStateFlow(load())
    override val settings: Flow<CoachAudioSettings> = state.asStateFlow()

    override suspend fun setRestBeep(enabled: Boolean) {
        defaults.setBool(enabled, KEY_REST_BEEP)
        state.value = state.value.copy(restBeep = enabled)
    }

    override suspend fun setVoiceAnnouncements(enabled: Boolean) {
        defaults.setBool(enabled, KEY_VOICE)
        state.value = state.value.copy(voiceAnnouncements = enabled)
    }

    private fun load(): CoachAudioSettings = CoachAudioSettings(
        restBeep = boolOrDefault(KEY_REST_BEEP),
        voiceAnnouncements = boolOrDefault(KEY_VOICE),
    )

    // NSUserDefaults.boolForKey returns false for an absent key; treat absent as
    // the default (true) instead.
    private fun boolOrDefault(key: String): Boolean =
        if (defaults.objectForKey(key) == null) true else defaults.boolForKey(key)

    private companion object {
        const val KEY_REST_BEEP = "hf.coachaudio.restBeep"
        const val KEY_VOICE = "hf.coachaudio.voiceAnnouncements"
    }
}
