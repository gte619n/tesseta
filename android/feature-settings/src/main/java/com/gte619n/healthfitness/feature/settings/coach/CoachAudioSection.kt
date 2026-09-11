package com.gte619n.healthfitness.feature.settings.coach

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.ui.components.SettingsCard
import com.gte619n.healthfitness.ui.components.SettingsToggleRow

/** Workout-coach audio prefs (PR2): rest-end beep + spoken set announcements. */
@Composable
fun CoachAudioSection(
    viewModel: CoachAudioSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsCard(title = "Workout coach") {
        SettingsToggleRow(
            label = "Rest-end beep",
            description = "Play a beep over headphones when rest ends",
            checked = settings.restBeep,
            onCheckedChange = viewModel::setRestBeep,
        )
        SettingsToggleRow(
            label = "Voice announcements",
            description = "Speak the exercise, weight, and reps at each set",
            checked = settings.voiceAnnouncements,
            onCheckedChange = viewModel::setVoiceAnnouncements,
        )
    }
}
