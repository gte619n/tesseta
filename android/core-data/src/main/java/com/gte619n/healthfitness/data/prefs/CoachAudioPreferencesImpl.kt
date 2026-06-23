package com.gte619n.healthfitness.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.domain.prefs.CoachAudioPreferences
import com.gte619n.healthfitness.domain.prefs.CoachAudioSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.coachAudioStore by preferencesDataStore("hf-coach-audio")

@Singleton
class CoachAudioPreferencesImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CoachAudioPreferences {

    private val keyRestBeep = booleanPreferencesKey("rest_beep")
    private val keyVoice = booleanPreferencesKey("voice_announcements")

    override val settings: Flow<CoachAudioSettings> = context.coachAudioStore.data.map { prefs ->
        CoachAudioSettings(
            restBeep = prefs[keyRestBeep] ?: true,
            voiceAnnouncements = prefs[keyVoice] ?: true,
        )
    }

    override suspend fun setRestBeep(enabled: Boolean) {
        context.coachAudioStore.edit { it[keyRestBeep] = enabled }
    }

    override suspend fun setVoiceAnnouncements(enabled: Boolean) {
        context.coachAudioStore.edit { it[keyVoice] = enabled }
    }
}
