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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.feature.workouts.program.ui.ActivateButton
import com.gte619n.healthfitness.feature.workouts.program.ui.ComplianceCalendar
import com.gte619n.healthfitness.feature.workouts.program.ui.PastSessionsSheet
import com.gte619n.healthfitness.feature.workouts.program.ui.PhaseRow
import com.gte619n.healthfitness.feature.workouts.program.ui.ProgramStatusPill
import com.gte619n.healthfitness.feature.workouts.program.ui.ThisWeekStrip
import com.gte619n.healthfitness.feature.workouts.session.ui.ParkedSessionBanner
import com.gte619n.healthfitness.feature.workouts.session.ui.ResumeSessionBanner
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

/**
 * The Workouts "This Week" landing (route body — the tabbed hub owns the header).
 * Focuses on the featured program: this week's sessions, a compliance calendar
 * with a streak, and the phase roadmap. Past workouts and AI refine are compact
 * icon actions rather than full rows.
 */
@Composable
fun WorkoutsLandingRoute(
    onOpenSession: (programId: String, scheduledId: String) -> Unit,
    onOpenWorkout: (programId: String, phaseId: String, dayId: String) -> Unit,
    onRefine: (programId: String?) -> Unit,
    onOpenProgramsTab: () -> Unit,
    viewModel: WorkoutsLandingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A successful restore re-materialized the draft; drop into the logger.
    LaunchedEffect(state.restoredSession) {
        state.restoredSession?.let {
            viewModel.consumeRestoredSession()
            onOpenSession(it.programId, it.scheduledId)
        }
    }

    WorkoutsLandingScreen(
        state = state,
        onOpenSession = onOpenSession,
        onOpenWorkout = { phaseId, dayId ->
            state.program?.let { onOpenWorkout(it.programId, phaseId, dayId) }
        },
        onRefine = { onRefine(state.program?.programId) },
        onOpenPastSessions = viewModel::openPastSessions,
        onDismissPastSessions = viewModel::dismissPastSessions,
        onDeletePastSession = viewModel::deleteSession,
        onActivate = viewModel::activate,
        onRestoreParked = viewModel::restoreParked,
        onDiscardParked = viewModel::discardParked,
        onPrevMonth = viewModel::prevMonth,
        onNextMonth = viewModel::nextMonth,
        onOpenProgramsTab = onOpenProgramsTab,
        onRetry = viewModel::refresh,
    )
}

@Composable
fun WorkoutsLandingScreen(
    state: WorkoutsLandingUiState,
    onOpenSession: (programId: String, scheduledId: String) -> Unit,
    onOpenWorkout: (phaseId: String, dayId: String) -> Unit,
    onRefine: () -> Unit,
    onOpenPastSessions: () -> Unit = {},
    onDismissPastSessions: () -> Unit = {},
    onDeletePastSession: (String) -> Unit = {},
    onActivate: () -> Unit = {},
    onRestoreParked: (ParkedCompletion) -> Unit = {},
    onDiscardParked: (ParkedCompletion) -> Unit = {},
    onPrevMonth: () -> Unit = {},
    onNextMonth: () -> Unit = {},
    onOpenProgramsTab: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    when {
        state.loading && state.program == null -> LoadingState(Modifier.fillMaxSize())
        state.error != null && state.program == null -> ErrorState(
            message = state.error,
            modifier = Modifier.fillMaxSize(),
            onRetry = onRetry,
        )
        state.program == null -> NoProgramState(
            hasAnyProgram = state.hasAnyProgram,
            onOpenProgramsTab = onOpenProgramsTab,
            onDesign = onRefine,
        )
        else -> LandingBody(
            state = state,
            onOpenSession = onOpenSession,
            onOpenWorkout = onOpenWorkout,
            onRefine = onRefine,
            onOpenPastSessions = onOpenPastSessions,
            onActivate = onActivate,
            onRestoreParked = onRestoreParked,
            onDiscardParked = onDiscardParked,
            onPrevMonth = onPrevMonth,
            onNextMonth = onNextMonth,
        )
    }

    if (state.showPastSessions) {
        PastSessionsSheet(
            sessions = state.pastSessions,
            onPick = { scheduledId ->
                onDismissPastSessions()
                state.program?.let { onOpenSession(it.programId, scheduledId) }
            },
            onDelete = onDeletePastSession,
            onDismiss = onDismissPastSessions,
        )
    }
}

@Composable
private fun NoProgramState(
    hasAnyProgram: Boolean,
    onOpenProgramsTab: () -> Unit,
    onDesign: () -> Unit,
) {
    if (hasAnyProgram) {
        EmptyState(
            title = "No active program",
            description = "Activate a program to see this week's workouts and your compliance here.",
            modifier = Modifier.fillMaxSize(),
            action = { ActivateButton(label = "Open Programs", onClick = onOpenProgramsTab) },
        )
    } else {
        EmptyState(
            title = "Design your first program",
            description = "Plan a periodized training program with the AI coach.",
            modifier = Modifier.fillMaxSize(),
            action = { ActivateButton(label = "Design a program", onClick = onDesign) },
        )
    }
}

@Composable
private fun LandingBody(
    state: WorkoutsLandingUiState,
    onOpenSession: (programId: String, scheduledId: String) -> Unit,
    onOpenWorkout: (phaseId: String, dayId: String) -> Unit,
    onRefine: () -> Unit,
    onOpenPastSessions: () -> Unit,
    onActivate: () -> Unit,
    onRestoreParked: (ParkedCompletion) -> Unit,
    onDiscardParked: (ParkedCompletion) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val program = state.program ?: return
    val phases = program.phases.sortedBy { it.orderIndex }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(8.dp))
                // Program title + status, with the compact past-workouts / refine actions.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            program.title,
                            style = Hf.type.headingMd.copy(fontSize = 16.sp),
                            color = Hf.colors.textPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        ProgramStatusPill(program.status)
                    }
                    IconAction(
                        icon = Icons.Outlined.CalendarMonth,
                        description = "Past workouts",
                        onClick = onOpenPastSessions,
                    )
                    IconAction(
                        icon = Icons.Outlined.AutoAwesome,
                        description = "Refine workout",
                        tint = Hf.colors.accent,
                        onClick = onRefine,
                    )
                }
                Spacer(Modifier.height(14.dp))

                if (state.activeDraft != null) {
                    ResumeSessionBanner(
                        draft = state.activeDraft,
                        onResume = {
                            onOpenSession(state.activeDraft.programId, state.activeDraft.scheduledId)
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (state.parkedCompletion != null) {
                    ParkedSessionBanner(
                        parked = state.parkedCompletion,
                        onRestore = { onRestoreParked(state.parkedCompletion) },
                        onDiscard = { onDiscardParked(state.parkedCompletion) },
                    )
                    if (state.parkedError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(state.parkedError, style = Hf.type.bodySm, color = Hf.colors.alert)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // A DRAFT needs activating; an ACTIVE program with an empty week can
                // re-materialize its schedule (e.g. after an edit).
                val needsActivate = program.status == ProgramStatus.DRAFT ||
                    (program.status == ProgramStatus.ACTIVE && state.thisWeek.isEmpty())
                if (needsActivate) {
                    ActivateButton(
                        label = if (program.status == ProgramStatus.DRAFT) {
                            "Activate program"
                        } else {
                            "Re-materialize sessions"
                        },
                        onClick = onActivate,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (state.activationIssues.isNotEmpty()) {
                    ActivationIssues(state.activationIssues)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        if (state.thisWeek.isNotEmpty()) {
            item {
                Column {
                    Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                        SectionTitle(text = "This week")
                    }
                    Spacer(Modifier.height(10.dp))
                    ThisWeekStrip(
                        scheduled = state.thisWeek,
                        today = state.today,
                        canStart = state.activeDraft == null,
                        onStartSession = { onOpenSession(program.programId, it.scheduledId) },
                        onReviewSession = { onOpenSession(program.programId, it.scheduledId) },
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                SectionTitle(text = "Compliance")
                Spacer(Modifier.height(10.dp))
                ComplianceCalendar(
                    month = state.visibleMonth,
                    scheduledByDate = state.monthDays.associate { it.date to it.status },
                    today = state.today,
                    onPrevMonth = onPrevMonth,
                    onNextMonth = onNextMonth,
                )
                Spacer(Modifier.height(10.dp))
                StreakRow(streak = state.streak)
                Spacer(Modifier.height(20.dp))
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                SectionTitle(text = "Roadmap")
            }
            Spacer(Modifier.height(10.dp))
        }

        itemsIndexed(phases, key = { _, p -> p.phaseId }) { index, phase ->
            Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                PhaseRow(
                    phase = phase,
                    isFirst = index == 0,
                    isLast = index == phases.lastIndex,
                    onOpenWorkout = onOpenWorkout,
                )
            }
        }
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = Hf.colors.textSecondary,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(9.dp))
            .background(Hf.colors.surface, RoundedCornerShape(9.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StreakRow(streak: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Outlined.LocalFireDepartment,
            contentDescription = null,
            tint = if (streak > 0) Hf.colors.accent else Hf.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (streak > 0) "$streak-day streak" else "No streak yet — log today's workout",
            style = Hf.type.bodyMd.copy(fontSize = 13.sp),
            color = if (streak > 0) Hf.colors.textPrimary else Hf.colors.textTertiary,
        )
    }
}

/** The validator issues from a failed activation, shown inline (IMPL-STAB G1). */
@Composable
private fun ActivationIssues(issues: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.alert.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .background(Hf.colors.alertBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Can't activate yet — fix these first:",
            style = Hf.type.bodyMd.copy(fontSize = 13.sp),
            color = Hf.colors.alert,
        )
        issues.forEach { issue ->
            Text("• $issue", style = Hf.type.bodySm, color = Hf.colors.textSecondary)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 1600)
@Composable
private fun WorkoutsLandingPreview() {
    HealthFitnessTheme {
        WorkoutsLandingScreen(
            state = WorkoutsLandingUiState(
                loading = false,
                program = ProgramFixtures.deepProgram,
                thisWeek = ProgramFixtures.thisWeek,
                monthDays = ProgramFixtures.thisWeek,
                streak = 3,
                today = LocalDate.parse("2026-06-03"),
                visibleMonth = java.time.YearMonth.of(2026, 6),
            ),
            onOpenSession = { _, _ -> },
            onOpenWorkout = { _, _ -> },
            onRefine = {},
        )
    }
}
