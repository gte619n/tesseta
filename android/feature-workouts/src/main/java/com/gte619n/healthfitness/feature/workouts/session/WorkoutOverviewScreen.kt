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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.gte619n.healthfitness.domain.workout.Block
import com.gte619n.healthfitness.domain.workout.PrescribedExercise
import com.gte619n.healthfitness.domain.workout.PrescribedSet
import com.gte619n.healthfitness.domain.workout.SessionStatus
import com.gte619n.healthfitness.domain.workout.WorkoutSession
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun WorkoutOverviewRoute(
    onStartWorkout: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: WorkoutOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkoutOverviewScreen(
        state = state,
        onStart = { viewModel.start(onStartWorkout) },
        onBack = onBack,
    )
}

@Composable
fun WorkoutOverviewScreen(
    state: WorkoutOverviewUiState,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 18.dp)
                .padding(top = 6.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Hf.colors.textPrimary,
                    )
                }
                Spacer(Modifier.height(0.dp))
                CapsLabel("Today's workout")
            }

            when {
                state.loading -> LoadingBlock()
                state.error != null && state.session == null -> ErrorBlock(state.error)
                state.session != null -> OverviewBody(state.session)
            }
        }

        if (state.session != null) {
            StartBar(
                label = if (state.session.status == SessionStatus.IN_PROGRESS) "Resume workout" else "Start workout",
                enabled = !state.starting,
                onStart = onStart,
            )
        }
    }
}

@Composable
private fun OverviewBody(session: WorkoutSession) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = session.title,
        style = Hf.type.headingLg.copy(fontSize = 22.sp),
        color = Hf.colors.textPrimary,
    )
    if (!session.focus.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = session.focus,
            style = Hf.type.bodyMd,
            color = Hf.colors.textSecondary,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "${session.exerciseCount} exercises · ~${session.estimatedMinutes} min",
        style = Hf.type.monoSm,
        color = Hf.colors.textTertiary,
    )

    Spacer(Modifier.height(16.dp))
    session.blocks.forEach { block ->
        BlockCard(block)
        Spacer(Modifier.height(11.dp))
    }
}

@Composable
private fun BlockCard(block: Block) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            SectionTitle(block.label ?: block.type.name.lowercase().replaceFirstChar { it.uppercase() })
            Spacer(Modifier.height(10.dp))
            block.exercises.forEachIndexed { i, exercise ->
                ExerciseRow(exercise)
                if (i < block.exercises.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ExerciseRow(exercise: PrescribedExercise) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exercise.name,
                style = Hf.type.bodyMd.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            if (!exercise.notes.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = exercise.notes,
                    style = Hf.type.bodySm.copy(fontSize = 11.sp),
                    color = Hf.colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(0.dp))
        Text(
            text = setsSummary(exercise.prescribedSets),
            style = Hf.type.monoSm,
            color = Hf.colors.textSecondary,
        )
    }
}

// e.g. "3 × 10 @ 135 lb", "4 × 0:45", "3 × 12"
private fun setsSummary(sets: List<PrescribedSet>): String {
    if (sets.isEmpty()) return ""
    val count = sets.size
    val first = sets.first()
    val per = when {
        first.targetSeconds != null -> formatSeconds(first.targetSeconds)
        first.targetReps != null -> first.targetReps.toString()
        else -> "—"
    }
    val weight = first.targetWeight?.let { " @ ${trimWeight(it)} ${first.weightUnit.name.lowercase()}" } ?: ""
    return "$count × $per$weight"
}

private fun formatSeconds(s: Int): String {
    val m = s / 60
    val r = s % 60
    return "$m:${r.toString().padStart(2, '0')}"
}

private fun trimWeight(w: Double): String =
    if (w == w.toLong().toDouble()) w.toLong().toString() else w.toString()

@Composable
private fun StartBar(label: String, enabled: Boolean, onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Hf.colors.accent,
                contentColor = Hf.colors.textInverse,
            ),
        ) {
            Text(label, style = Hf.type.bodyMd.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Hf.colors.accent)
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        Text(message, style = Hf.type.bodyMd, color = Hf.colors.alert)
    }
}
