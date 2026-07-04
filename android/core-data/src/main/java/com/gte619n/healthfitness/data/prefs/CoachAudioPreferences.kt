package com.gte619n.healthfitness.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.domain.prefs.CoachAudioSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.coachAudioStore by preferencesDataStore("hf-coach-audio")

@Singleton
// Concrete @Inject repository (single implementation — no domain interface).
class CoachAudioPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val keyRestBeep = booleanPreferencesKey("rest_beep")
    private val keyVoice = booleanPreferencesKey("voice_announcements")

    val settings: Flow<CoachAudioSettings> = context.coachAudioStore.data.map { prefs ->
        CoachAudioSettings(
            restBeep = prefs[keyRestBeep] ?: true,
            voiceAnnouncements = prefs[keyVoice] ?: true,
        )
    }

    suspend fun setRestBeep(enabled: Boolean) {
        context.coachAudioStore.edit { it[keyRestBeep] = enabled }
    }

    suspend fun setVoiceAnnouncements(enabled: Boolean) {
        context.coachAudioStore.edit { it[keyVoice] = enabled }
    }
}
