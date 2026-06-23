package com.gte619n.healthfitness.feature.settings.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/** Workout-coach audio prefs (PR2): rest-end beep + spoken set announcements. */
@Composable
fun CoachAudioSection(
    viewModel: CoachAudioSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    HfCard(transparent = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Workout coach", style = Hf.type.headingSm, color = Hf.colors.textPrimary)

            ToggleRow(
                label = "Rest-end beep",
                description = "Play a beep over headphones when rest ends",
                checked = settings.restBeep,
                onCheckedChange = viewModel::setRestBeep,
            )
            ToggleRow(
                label = "Voice announcements",
                description = "Speak the exercise, weight, and reps at each set",
                checked = settings.voiceAnnouncements,
                onCheckedChange = viewModel::setVoiceAnnouncements,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Text(description, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Hf.colors.textInverse,
                checkedTrackColor = Hf.colors.accent,
            ),
        )
    }
}
