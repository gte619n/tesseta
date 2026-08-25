package com.gte619n.healthfitness.feature.settings.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Workout-preferences editor: free-text standing instructions the program designer
 * honors on every build (e.g. "no bent-over rows or anything that stresses my
 * lower back"). Saved to the backend so it applies to every future program, not
 * just one chat.
 */
@Composable
fun WorkoutPreferencesSection(
    viewModel: WorkoutPreferencesViewModel = hiltViewModel(),
) {
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()

    // Re-seed the editor whenever the stored value changes (initial load, a save
    // that round-trips, or a cross-device sync push). Keystrokes keep the key
    // unchanged, so in-progress edits are preserved.
    var text by remember(stored) { mutableStateOf(stored) }
    val dirty = text.trim() != stored.trim()

    HfCard(transparent = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Workout preferences", style = Hf.type.headingSm, color = Hf.colors.textPrimary)
            Text(
                "Standing notes the program builder follows every time — exercises to "
                    + "avoid, injuries to work around, or how you like to train.",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )

            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(viewModel.maxLength)
                    viewModel.onEdited()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = {
                    Text(
                        "e.g. No bent-over rows or deadlifts — they hurt my lower back. "
                            + "Prefer machines over free weights for legs.",
                        style = Hf.type.bodyMd,
                    )
                },
                textStyle = Hf.type.bodyMd,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                val status = when (saveState) {
                    WorkoutPreferencesViewModel.SaveState.SAVING -> "Saving…"
                    WorkoutPreferencesViewModel.SaveState.SAVED -> "Saved"
                    WorkoutPreferencesViewModel.SaveState.ERROR -> "Couldn't save — try again"
                    WorkoutPreferencesViewModel.SaveState.IDLE -> null
                }
                if (status != null) {
                    Text(
                        status,
                        style = Hf.type.bodySm,
                        color = if (saveState == WorkoutPreferencesViewModel.SaveState.ERROR) {
                            Hf.colors.alert
                        } else {
                            Hf.colors.textTertiary
                        },
                    )
                }
                Button(
                    onClick = { viewModel.save(text) },
                    enabled = dirty && saveState != WorkoutPreferencesViewModel.SaveState.SAVING,
                ) {
                    Text("Save")
                }
            }
        }
    }
}
