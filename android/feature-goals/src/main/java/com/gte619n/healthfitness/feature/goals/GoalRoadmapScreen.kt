package com.gte619n.healthfitness.feature.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.goals.Phase
import com.gte619n.healthfitness.domain.goals.PhaseStatus
import com.gte619n.healthfitness.domain.goals.Step
import com.gte619n.healthfitness.domain.goals.StepKind
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.components.ProgressTrack
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun GoalRoadmapRoute(
    onBack: () -> Unit,
    viewModel: GoalRoadmapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GoalRoadmapScreen(
        state = state,
        onBack = onBack,
        onToggleStep = viewModel::toggleStep,
        onResetStep = viewModel::resetStepToAuto,
    )
}

@Composable
fun GoalRoadmapScreen(
    state: GoalRoadmapUiState,
    onBack: () -> Unit,
    onToggleStep: (phaseId: String, stepId: String, done: Boolean) -> Unit,
    onResetStep: (phaseId: String, stepId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        RoadmapTopBar(title = state.goal?.title ?: "Goal", onBack = onBack)

        when {
            state.loading -> Centered { CircularProgressIndicator(color = Hf.colors.accent) }
            state.error != null && state.goal == null ->
                Centered { Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert) }
            state.goal != null -> RoadmapBody(
                goal = state.goal,
                pending = state.pendingStepIds,
                onToggleStep = onToggleStep,
                onResetStep = onResetStep,
            )
        }
    }
}

@Composable
private fun RoadmapBody(
    goal: GoalDeep,
    pending: Set<String>,
    onToggleStep: (String, String, Boolean) -> Unit,
    onResetStep: (String, String) -> Unit,
) {
    val phases = goal.phases.sortedBy { it.orderIndex }
    val progress = goal.progress()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        item {
            GoalHeader(goal = goal, summary = progress.summary, fraction = progress.stepFraction)
            Spacer(Modifier.height(16.dp))
        }
        itemsIndexed(phases, key = { _, p -> p.phaseId }) { index, phase ->
            PhaseRow(
                phase = phase,
                isFirst = index == 0,
                isLast = index == phases.lastIndex,
                pending = pending,
                onToggleStep = onToggleStep,
                onResetStep = onResetStep,
            )
        }
    }
}

@Composable
private fun GoalHeader(goal: GoalDeep, summary: String, fraction: Float) {
    Column {
        Pill(goal.domain.label, HfTone.Neutral)
        Spacer(Modifier.height(8.dp))
        Text(goal.title, style = Hf.type.headingLg.copy(fontSize = 19.sp), color = Hf.colors.textPrimary)
        if (goal.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(goal.description, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapsLabel("Target ${formatCapsDate(goal.targetDate)}", color = Hf.colors.textTertiary)
        }
        Spacer(Modifier.height(8.dp))
        Text(summary, style = Hf.type.monoMd, color = Hf.colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        ProgressTrack(pct = fraction, color = Hf.colors.accent, heightDp = 3)
    }
}

@Composable
private fun PhaseRow(
    phase: Phase,
    isFirst: Boolean,
    isLast: Boolean,
    pending: Set<String>,
    onToggleStep: (String, String, Boolean) -> Unit,
    onResetStep: (String, String) -> Unit,
) {
    val behind = phase.isBehindSchedule()
    val locked = phase.status == PhaseStatus.LOCKED
    Row(modifier = Modifier.fillMaxWidth()) {
        Spine(status = phase.status, behind = behind, isFirst = isFirst, isLast = isLast)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 18.dp)
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
                .background(Hf.colors.surface, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp)
                .then(if (locked) Modifier.alpha(0.6f) else Modifier),
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
                PhaseStatusPill(phase.status, behind)
            }
            Spacer(Modifier.height(4.dp))
            CapsLabel(
                formatDateRange(phase.targetStartDate, phase.targetEndDate),
                color = Hf.colors.textTertiary,
            )
            Spacer(Modifier.height(11.dp))
            phase.steps.sortedBy { it.orderIndex }.forEach { step ->
                StepRow(
                    step = step,
                    locked = locked,
                    pending = step.stepId in pending,
                    onToggle = { onToggleStep(phase.phaseId, step.stepId, it) },
                    onReset = { onResetStep(phase.phaseId, step.stepId) },
                )
            }
        }
    }
}

@Composable
private fun PhaseStatusPill(status: PhaseStatus, behind: Boolean) {
    if (behind && status != PhaseStatus.COMPLETED) {
        Pill("Behind schedule", HfTone.Warn)
        return
    }
    when (status) {
        PhaseStatus.COMPLETED -> Pill("Completed", HfTone.Good)
        PhaseStatus.ACTIVE -> Pill("Active", HfTone.Good)
        PhaseStatus.LOCKED -> Pill("Locked", HfTone.Neutral)
    }
}

/**
 * Left-hand spine: a vertical connector line with a node for this phase.
 * completed = olive filled; active = larger olive-outlined; locked = muted;
 * past-due = warn tint.
 */
@Composable
private fun Spine(status: PhaseStatus, behind: Boolean, isFirst: Boolean, isLast: Boolean) {
    val nodeColor: Color = when {
        behind && status != PhaseStatus.COMPLETED -> Hf.colors.warn
        status == PhaseStatus.COMPLETED -> Hf.colors.accent
        status == PhaseStatus.ACTIVE -> Hf.colors.accent
        else -> Hf.colors.muted
    }
    val lineAbove = if (status == PhaseStatus.LOCKED) Hf.colors.borderDefault else Hf.colors.accent
    val lineBelow = if (status == PhaseStatus.COMPLETED) Hf.colors.accent else Hf.colors.borderDefault

    Column(
        modifier = Modifier.width(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(8.dp)
                .background(if (isFirst) Color.Transparent else lineAbove),
        )
        // Node: active is a larger outlined ring; completed is filled with a check.
        if (status == PhaseStatus.ACTIVE) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(Hf.colors.surface, CircleShape)
                    .border(2.dp, nodeColor, CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(nodeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (status == PhaseStatus.COMPLETED) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = Hf.colors.textInverse,
                        modifier = Modifier.size(8.dp),
                    )
                } else if (status == PhaseStatus.LOCKED) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = Hf.colors.surface,
                        modifier = Modifier.size(7.dp),
                    )
                }
            }
        }
        if (!isLast) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(lineBelow),
            )
        }
    }
}

@Composable
private fun StepRow(
    step: Step,
    locked: Boolean,
    pending: Boolean,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = step.done,
            enabled = !locked && !pending,
            onCheckedChange = { onToggle(!step.done) },
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                step.title,
                style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                color = if (step.done) Hf.colors.textTertiary else Hf.colors.textPrimary,
            )
            // Metric readout for bound steps. The deep response gives target +
            // done state but no live current value, so we render the target
            // condition and the done state. TODO: surface a live "current"
            // value if the StepResponse gains one.
            step.metric?.let { m ->
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${m.metricKey} ${m.comparator.symbol} ${formatTarget(m.targetValue)}" +
                            windowSuffix(step.kind, m.windowDays),
                        style = Hf.type.monoSm,
                        color = Hf.colors.textSecondary,
                    )
                    if (step.done && !step.manualOverride) {
                        Pill("Auto", HfTone.Good)
                    }
                    if (step.metricRegressed == true) {
                        Pill("Metric regressed", HfTone.Warn)
                    }
                }
            }
            // Reset-to-auto affordance on overridden steps.
            if (step.manualOverride && step.kind != StepKind.MANUAL && !locked) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.clickable(enabled = !pending) { onReset() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = Hf.colors.accent,
                        modifier = Modifier.size(11.dp),
                    )
                    Text("Reset to auto", style = Hf.type.capsSm, color = Hf.colors.accent)
                }
            }
        }
    }
}

@Composable
private fun Checkbox(checked: Boolean, enabled: Boolean, onCheckedChange: () -> Unit) {
    val border = if (checked) Hf.colors.accent else Hf.colors.borderStrong
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(if (checked) Hf.colors.accent else Hf.colors.surface, RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .then(if (enabled) Modifier.clickable { onCheckedChange() } else Modifier.alpha(0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = if (checked) "Done" else "Not done",
                tint = Hf.colors.textInverse,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun RoadmapTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.canvas)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
                .background(Hf.colors.surface, RoundedCornerShape(8.dp))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = Hf.colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = Hf.type.headingMd.copy(fontSize = 15.sp),
            color = Hf.colors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun formatTarget(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun windowSuffix(kind: StepKind, windowDays: Int?): String =
    if (kind == StepKind.SUSTAINED && windowDays != null) " for ${windowDays}d" else ""

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 1100)
@Composable
private fun GoalRoadmapPreview() {
    HealthFitnessTheme {
        GoalRoadmapScreen(
            state = GoalRoadmapUiState(loading = false, goal = GoalsFixtures.deepGoal),
            onBack = {},
            onToggleStep = { _, _, _ -> },
            onResetStep = { _, _ -> },
        )
    }
}
