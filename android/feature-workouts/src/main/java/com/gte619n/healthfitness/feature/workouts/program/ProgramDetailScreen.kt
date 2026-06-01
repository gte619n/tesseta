package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.feature.workouts.program.ui.ExerciseDetailSheet
import com.gte619n.healthfitness.feature.workouts.program.ui.PhaseMeta
import com.gte619n.healthfitness.feature.workouts.program.ui.PhaseSpineNode
import com.gte619n.healthfitness.feature.workouts.program.ui.PhaseStatusPill
import com.gte619n.healthfitness.feature.workouts.program.ui.ProgramStatusPill
import com.gte619n.healthfitness.feature.workouts.program.ui.ThisWeekStrip
import com.gte619n.healthfitness.feature.workouts.program.ui.WorkoutDayCard
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun ProgramDetailRoute(
    onBack: () -> Unit,
    onOpenGoal: (String) -> Unit,
    viewModel: ProgramDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgramDetailScreen(
        state = state,
        onBack = onBack,
        onOpenGoal = onOpenGoal,
        onRetry = viewModel::refresh,
    )
}

@Composable
fun ProgramDetailScreen(
    state: ProgramDetailUiState,
    onBack: () -> Unit,
    onOpenGoal: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var selectedExercise by remember { mutableStateOf<ExerciseSummary?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = state.program?.title ?: "Program",
            subtitle = "Your periodized plan",
            onBack = onBack,
        )
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize())
            state.error != null && state.program == null -> ErrorState(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )
            state.program != null -> ProgramBody(
                program = state.program,
                thisWeek = state.thisWeek,
                onOpenGoal = onOpenGoal,
                onOpenExercise = { selectedExercise = it },
            )
        }
    }

    selectedExercise?.let { exercise ->
        ExerciseDetailSheet(summary = exercise, onDismiss = { selectedExercise = null })
    }
}

@Composable
private fun ProgramBody(
    program: WorkoutProgram,
    thisWeek: List<com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout>,
    onOpenGoal: (String) -> Unit,
    onOpenExercise: (ExerciseSummary) -> Unit,
) {
    val phases = program.phases.sortedBy { it.orderIndex }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(4.dp))
                ProgramStatusPill(program.status)
                if (!program.description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(program.description!!, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
                }
                Spacer(Modifier.height(8.dp))
                CapsLabel(
                    trainingDaysSummary(program.trainingDays),
                    color = Hf.colors.textTertiary,
                )
                if (program.goalId != null) {
                    Spacer(Modifier.height(12.dp))
                    GoalLinkRow(
                        title = program.goalTitle,
                        onClick = { onOpenGoal(program.goalId!!) },
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }

        if (thisWeek.isNotEmpty()) {
            item {
                Column {
                    Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                        SectionTitle(text = "This week")
                    }
                    Spacer(Modifier.height(10.dp))
                    ThisWeekStrip(scheduled = thisWeek)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                SectionTitle(text = "Phases")
            }
            Spacer(Modifier.height(10.dp))
        }

        itemsIndexed(phases, key = { _, p -> p.phaseId }) { index, phase ->
            Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                PhaseRow(
                    phase = phase,
                    isFirst = index == 0,
                    isLast = index == phases.lastIndex,
                    onOpenExercise = onOpenExercise,
                )
            }
        }
    }
}

@Composable
private fun GoalLinkRow(title: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .background(Hf.colors.accentBg, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Attached to goal: ${title ?: "View goal"}",
            style = Hf.type.bodyMd.copy(fontSize = 13.sp),
            color = Hf.colors.accentDim,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = "Open goal",
            tint = Hf.colors.accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PhaseRow(
    phase: ProgramPhase,
    isFirst: Boolean,
    isLast: Boolean,
    onOpenExercise: (ExerciseSummary) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        PhaseSpineNode(status = phase.status, isFirst = isFirst, isLast = isLast)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    phase.title,
                    style = Hf.type.headingMd.copy(fontSize = 14.sp),
                    color = Hf.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PhaseStatusPill(phase.status)
            }
            Spacer(Modifier.height(5.dp))
            PhaseMeta(phase)
            Spacer(Modifier.height(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                phase.days.sortedBy { it.orderIndex }.forEach { day ->
                    WorkoutDayCard(day = day, onOpenExercise = onOpenExercise)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 1400)
@Composable
private fun ProgramDetailPreview() {
    HealthFitnessTheme {
        ProgramDetailScreen(
            state = ProgramDetailUiState(
                loading = false,
                program = ProgramFixtures.deepProgram,
                thisWeek = ProgramFixtures.thisWeek,
            ),
            onBack = {},
            onOpenGoal = {},
            onRetry = {},
        )
    }
}
