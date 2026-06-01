package com.gte619n.healthfitness.feature.workouts.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workout.ExerciseResult
import com.gte619n.healthfitness.domain.workout.SessionSummary
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun WorkoutSummaryRoute(
    onDone: () -> Unit,
    viewModel: WorkoutSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkoutSummaryScreen(state = state, onDone = onDone)
}

@Composable
fun WorkoutSummaryScreen(state: WorkoutSummaryUiState, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Hf.colors.canvas),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 18.dp)
                .padding(top = 16.dp, bottom = 16.dp),
        ) {
            when {
                state.loading -> CenterSpinner()
                state.error != null -> Text(state.error, color = Hf.colors.alert, style = Hf.type.bodyMd)
                state.completed != null -> {
                    CapsLabel("Workout complete")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.completed.title,
                        style = Hf.type.headingLg.copy(fontSize = 24.sp),
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(16.dp))
                    SummaryBody(state.completed.summary)
                }
            }
        }

        if (state.completed != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Hf.colors.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Hf.colors.accent,
                        contentColor = Hf.colors.textInverse,
                    ),
                ) {
                    Text("Done", style = Hf.type.bodyMd.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun SummaryBody(summary: SessionSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StatCell("DURATION", formatDuration(summary.durationSeconds), Modifier.weight(1f))
        StatCell("VOLUME", "${trimNumber(summary.totalVolume)} lb", Modifier.weight(1f))
    }
    Spacer(Modifier.height(9.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StatCell("SETS", "${summary.setsCompleted}/${summary.setsPrescribed}", Modifier.weight(1f))
        StatCell("CALORIES", "${summary.estimatedCalories}", Modifier.weight(1f))
    }

    if (!summary.aiRecap.isNullOrBlank()) {
        Spacer(Modifier.height(14.dp))
        HfCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(15.dp)) {
                SectionTitle("Coach's note")
                Spacer(Modifier.height(8.dp))
                Text(summary.aiRecap, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
            }
        }
    }

    if (summary.perExercise.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        HfCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(15.dp)) {
                SectionTitle("Exercises")
                Spacer(Modifier.height(10.dp))
                summary.perExercise.forEachIndexed { i, r ->
                    ExerciseResultRow(r)
                    if (i < summary.perExercise.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(label, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(4.dp))
            Text(value, style = Hf.type.monoMd.copy(fontSize = 18.sp), color = Hf.colors.textPrimary)
        }
    }
}

@Composable
private fun ExerciseResultRow(r: ExerciseResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                r.name,
                style = Hf.type.bodyMd.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            Text(r.topSet, style = Hf.type.monoSm, color = Hf.colors.textTertiary)
        }
        Text("${trimNumber(r.volume)} lb", style = Hf.type.monoSm, color = Hf.colors.textSecondary)
    }
}

@Composable
private fun CenterSpinner() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Hf.colors.accent)
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    return if (m >= 60) "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m" else "$m min"
}

private fun trimNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
