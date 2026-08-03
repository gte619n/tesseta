package com.gte619n.healthfitness.feature.settings.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Workout-streak preferences: the number of workouts per week needed to keep the
 * consecutive-weeks streak on the Workouts landing alive. A simple stepper,
 * bounded to the supported range.
 */
@Composable
fun WorkoutStreakSection(
    viewModel: WorkoutStreakSettingsViewModel = hiltViewModel(),
) {
    val target by viewModel.weeklyTarget.collectAsStateWithLifecycle()

    HfCard(transparent = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Workout streak", style = Hf.type.headingSm, color = Hf.colors.textPrimary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Weekly goal", style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                    Text(
                        "Workouts per week to keep your streak",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                    )
                }
                Stepper(
                    value = target,
                    onDecrement = { viewModel.setTarget(target - 1) },
                    onIncrement = { viewModel.setTarget(target + 1) },
                    canDecrement = target > viewModel.minTarget,
                    canIncrement = target < viewModel.maxTarget,
                )
            }
        }
    }
}

@Composable
private fun Stepper(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onDecrement,
            enabled = canDecrement,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
        ) {
            Text("−", style = Hf.type.headingSm)
        }
        Text(
            value.toString(),
            style = Hf.type.headingSm,
            color = Hf.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp),
        )
        OutlinedButton(
            onClick = onIncrement,
            enabled = canIncrement,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
        ) {
            Text("+", style = Hf.type.headingSm)
        }
    }
}
