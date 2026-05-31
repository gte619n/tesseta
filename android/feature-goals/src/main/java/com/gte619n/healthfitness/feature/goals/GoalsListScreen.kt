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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalStatus
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

@Composable
fun GoalsListRoute(
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: GoalsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GoalsListScreen(
        state = state,
        onFilterChange = viewModel::setFilter,
        onOpenGoal = onOpenGoal,
        onNewGoal = onNewGoal,
        onBack = onBack,
    )
}

@Composable
fun GoalsListScreen(
    state: GoalsListUiState,
    onFilterChange: (GoalsFilter) -> Unit,
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = "Goals",
            subtitle = "Your roadmap of health objectives",
            onBack = onBack,
            trailing = {
                // "New goal" — opens the goal-planning chat.
                Row(
                    modifier = Modifier
                        .background(Hf.colors.accent, RoundedCornerShape(8.dp))
                        .clickable { onNewGoal() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "New goal",
                        tint = Hf.colors.textInverse,
                        modifier = Modifier.size(15.dp),
                    )
                    Text("NEW GOAL", style = Hf.type.capsSm, color = Hf.colors.textInverse)
                }
            },
        )
        FilterRow(active = state.filter, onFilterChange = onFilterChange)
        Spacer(Modifier.height(4.dp))

        when {
            state.loading -> CenteredMessage { CircularProgressIndicator(color = Hf.colors.accent) }
            state.error != null -> CenteredMessage {
                Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert)
            }
            state.goals.isEmpty() -> CenteredMessage {
                Text(
                    "No ${state.filter.label.lowercase()} goals yet.",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textTertiary,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(state.goals, key = { it.goalId }) { goal ->
                    GoalCard(goal = goal, onClick = { onOpenGoal(goal.goalId) })
                }
            }
        }
    }
}

@Composable
private fun FilterRow(active: GoalsFilter, onFilterChange: (GoalsFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GoalsFilter.entries.forEach { filter ->
            val isActive = filter == active
            Box(
                modifier = Modifier
                    .background(
                        if (isActive) Hf.colors.accentBg else Hf.colors.surface,
                        RoundedCornerShape(7.dp),
                    )
                    .border(
                        0.5.dp,
                        if (isActive) Hf.colors.accent else Hf.colors.borderDefault,
                        RoundedCornerShape(7.dp),
                    )
                    .clickable { onFilterChange(filter) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    filter.label,
                    style = Hf.type.bodyMd.copy(fontSize = 12.sp),
                    color = if (isActive) Hf.colors.accentDim else Hf.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun GoalCard(goal: Goal, onClick: () -> Unit) {
    val behind = goal.status == GoalStatus.ACTIVE &&
        parseDate(goal.targetDate)?.let { LocalDate.now().isAfter(it) } == true
    HfCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(if (behind) Modifier.alpha(0.7f) else Modifier),
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(goal.domain.label, HfTone.Neutral)
                if (behind) {
                    Pill("Behind schedule", HfTone.Warn)
                } else {
                    CapsLabel(formatCapsDate(goal.targetDate), color = Hf.colors.textTertiary)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                goal.title,
                style = Hf.type.headingMd.copy(fontSize = 15.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(11.dp))
            // Compact phase spine. The shallow list endpoint carries only the
            // phase count + goal status (no per-phase status), so segments are
            // tinted by goal status. The roadmap detail shows true per-phase
            // state. TODO: enrich here if the list endpoint gains phase status.
            PhaseSpine(phaseCount = goal.phaseOrder.size, status = goal.status, behind = behind)
            Spacer(Modifier.height(9.dp))
            CapsLabel(
                "${goal.phaseOrder.size} phases",
                color = Hf.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PhaseSpine(phaseCount: Int, status: GoalStatus, behind: Boolean) {
    val segColor: Color = when {
        behind -> Hf.colors.muted
        status == GoalStatus.COMPLETED -> Hf.colors.accent
        status == GoalStatus.ARCHIVED -> Hf.colors.muted
        else -> Hf.colors.accentBg
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(phaseCount.coerceAtLeast(1)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .background(segColor, RoundedCornerShape(3.dp))
                    .border(0.5.dp, Hf.colors.accent.copy(alpha = 0.4f), RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun GoalsListPreview() {
    HealthFitnessTheme {
        GoalsListScreen(
            state = GoalsListUiState(loading = false, goals = GoalsFixtures.listGoals),
            onFilterChange = {},
            onOpenGoal = {},
            onNewGoal = {},
        )
    }
}
