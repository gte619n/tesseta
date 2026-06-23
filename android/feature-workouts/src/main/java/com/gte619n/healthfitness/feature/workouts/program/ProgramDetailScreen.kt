package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HistoryEdu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.program.ui.PhaseMeta
import com.gte619n.healthfitness.feature.workouts.program.ui.PhaseSpineNode
import com.gte619n.healthfitness.feature.workouts.program.ui.PhaseStatusPill
import com.gte619n.healthfitness.feature.workouts.program.ui.ProgramStatusPill
import com.gte619n.healthfitness.feature.workouts.program.ui.ThisWeekStrip
import com.gte619n.healthfitness.feature.workouts.program.ui.WorkoutDayRow
import com.gte619n.healthfitness.feature.workouts.session.ui.ParkedSessionBanner
import com.gte619n.healthfitness.feature.workouts.session.ui.ResumeSessionBanner
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

@Composable
fun ProgramDetailRoute(
    onBack: () -> Unit,
    onOpenGoal: (String) -> Unit,
    onOpenWorkout: (programId: String, phaseId: String, dayId: String) -> Unit,
    onOpenSession: (programId: String, scheduledId: String) -> Unit,
    onRefineWithAi: (programId: String) -> Unit = {},
    viewModel: ProgramDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A successful restore re-materialized the draft; drop into the logger.
    LaunchedEffect(state.restoredSession) {
        state.restoredSession?.let {
            viewModel.consumeRestoredSession()
            onOpenSession(it.programId, it.scheduledId)
        }
    }

    ProgramDetailScreen(
        state = state,
        onBack = onBack,
        onOpenGoal = onOpenGoal,
        onOpenWorkout = onOpenWorkout,
        onOpenSession = onOpenSession,
        onRefineWithAi = onRefineWithAi,
        onActivate = viewModel::activate,
        onRetry = viewModel::refresh,
        onRestoreParked = viewModel::restoreParked,
        onDiscardParked = viewModel::discardParked,
        onStartEdit = viewModel::startEdit,
        onCancelEdit = viewModel::cancelEdit,
        onSaveEdit = viewModel::saveEdit,
        onOpenPastSessions = viewModel::openPastSessions,
        onDismissPastSessions = viewModel::dismissPastSessions,
        onDeletePastSession = viewModel::deleteSession,
        onApplyNutrition = viewModel::applyNutrition,
        onConsumeApplied = viewModel::consumeAppliedNutrition,
    )
}

@Composable
fun ProgramDetailScreen(
    state: ProgramDetailUiState,
    onBack: () -> Unit,
    onOpenGoal: (String) -> Unit,
    onOpenWorkout: (programId: String, phaseId: String, dayId: String) -> Unit,
    onOpenSession: (programId: String, scheduledId: String) -> Unit,
    onRefineWithAi: (programId: String) -> Unit = {},
    onActivate: () -> Unit = {},
    onRetry: () -> Unit,
    onRestoreParked: (ParkedCompletion) -> Unit = {},
    onDiscardParked: (ParkedCompletion) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onSaveEdit: (title: String, description: String?) -> Unit = { _, _ -> },
    onOpenPastSessions: () -> Unit = {},
    onDismissPastSessions: () -> Unit = {},
    onDeletePastSession: (scheduledId: String) -> Unit = {},
    onApplyNutrition: () -> Unit = {},
    onConsumeApplied: () -> Unit = {},
) {
    var confirmNutrition by remember { mutableStateOf(false) }
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
            // IMPL-STAB G4: an edit affordance for title/description.
            trailing = if (state.program != null) {
                {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit program",
                        tint = Hf.colors.textSecondary,
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { onStartEdit() }
                            .padding(8.dp),
                    )
                }
            } else {
                null
            },
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
                hasPastSessions = state.pastSessions.isNotEmpty(),
                activationIssues = state.activationIssues,
                activeDraft = state.activeDraft,
                parkedCompletion = state.parkedCompletion,
                parkedError = state.parkedError,
                today = state.today,
                nutritionGuidance = state.nutritionGuidance,
                applyingNutrition = state.applyingNutrition,
                onRequestApplyNutrition = { confirmNutrition = true },
                onOpenGoal = onOpenGoal,
                onOpenWorkout = { phaseId, dayId ->
                    onOpenWorkout(state.program.programId, phaseId, dayId)
                },
                onOpenSession = onOpenSession,
                onRefineWithAi = { onRefineWithAi(state.program.programId) },
                onActivate = onActivate,
                onLogPastSession = onOpenPastSessions,
                onRestoreParked = onRestoreParked,
                onDiscardParked = onDiscardParked,
            )
        }
    }

    if (state.editing && state.program != null) {
        EditProgramSheet(
            initialTitle = state.program.title,
            initialDescription = state.program.description.orEmpty(),
            saving = state.savingEdit,
            error = state.error,
            onSave = onSaveEdit,
            onDismiss = onCancelEdit,
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

    // Confirm before overwriting the current nutrition target with the plan's.
    val guidance = state.nutritionGuidance
    if (confirmNutrition && guidance != null) {
        AlertDialog(
            onDismissRequest = { confirmNutrition = false },
            title = { Text("Apply as nutrition target?", style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
            text = {
                Text(
                    "Set your daily nutrition target to ${guidanceSummary(guidance)} from this " +
                        "program. This replaces your current target.",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNutrition = false
                    onApplyNutrition()
                }) { Text("Apply target", color = Hf.colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNutrition = false }) {
                    Text("Cancel", color = Hf.colors.textTertiary)
                }
            },
            containerColor = Hf.colors.surface,
        )
    }

    state.appliedNutrition?.let { applied ->
        AlertDialog(
            onDismissRequest = onConsumeApplied,
            title = { Text("Nutrition target updated", style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
            text = { Text(macrosSummary(applied), style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = onConsumeApplied) { Text("Done", color = Hf.colors.accent) }
            },
            containerColor = Hf.colors.surface,
        )
    }
}

/** "2,640 kcal · 200P / 280C / 80F" — what applying this guidance sets (calories macro-derived). */
private fun guidanceSummary(g: NutritionGuidance): String {
    val derived = if (g.proteinG == null && g.carbsG == null && g.fatG == null) {
        null
    } else {
        (g.proteinG ?: 0) * 4 + (g.carbsG ?: 0) * 4 + (g.fatG ?: 0) * 9
    }
    val kcal = derived ?: g.kcal
    val parts = mutableListOf<String>()
    if (kcal != null) parts += "$kcal kcal"
    val macros = listOfNotNull(
        g.proteinG?.let { "${it}P" },
        g.carbsG?.let { "${it}C" },
        g.fatG?.let { "${it}F" },
    ).joinToString(" / ")
    if (macros.isNotEmpty()) parts += macros
    return parts.joinToString(" · ")
}

private fun macrosSummary(m: Macros): String {
    val parts = mutableListOf<String>()
    m.caloriesKcal?.let { parts += "${it.toLong()} kcal" }
    val macros = listOfNotNull(
        m.proteinGrams?.let { "${it.toLong()}P" },
        m.carbsGrams?.let { "${it.toLong()}C" },
        m.fatGrams?.let { "${it.toLong()}F" },
    ).joinToString(" / ")
    if (macros.isNotEmpty()) parts += macros
    return parts.joinToString(" · ")
}

/** The program's nutrition guidance with an "Apply as nutrition target" action (IMPL-COACH). */
@Composable
private fun NutritionGuidanceCard(
    guidance: NutritionGuidance,
    applying: Boolean,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        CapsLabel("Nutrition guidance", color = Hf.colors.textTertiary)
        Spacer(Modifier.height(6.dp))
        Text(guidanceSummary(guidance), style = Hf.type.monoMd, color = Hf.colors.textPrimary)
        guidance.note?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .background(Hf.colors.accent, RoundedCornerShape(6.dp))
                .clickable(enabled = !applying) { onApply() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (applying) "APPLYING…" else "APPLY AS NUTRITION TARGET",
                style = Hf.type.capsMd,
                color = Hf.colors.textInverse,
            )
        }
    }
}

@Composable
private fun ProgramBody(
    program: WorkoutProgram,
    thisWeek: List<com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout>,
    hasPastSessions: Boolean,
    activationIssues: List<String>,
    activeDraft: WorkoutSessionDraft?,
    parkedCompletion: ParkedCompletion?,
    parkedError: String?,
    today: LocalDate,
    nutritionGuidance: NutritionGuidance? = null,
    applyingNutrition: Boolean = false,
    onRequestApplyNutrition: () -> Unit = {},
    onOpenGoal: (String) -> Unit,
    onOpenWorkout: (phaseId: String, dayId: String) -> Unit,
    onOpenSession: (programId: String, scheduledId: String) -> Unit,
    onRefineWithAi: () -> Unit,
    onActivate: () -> Unit,
    onLogPastSession: () -> Unit,
    onRestoreParked: (ParkedCompletion) -> Unit,
    onDiscardParked: (ParkedCompletion) -> Unit,
) {
    val phases = program.phases.sortedBy { it.orderIndex }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (nutritionGuidance != null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                    Spacer(Modifier.height(4.dp))
                    NutritionGuidanceCard(
                        guidance = nutritionGuidance,
                        applying = applyingNutrition,
                        onApply = onRequestApplyNutrition,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (activeDraft != null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                    Spacer(Modifier.height(4.dp))
                    ResumeSessionBanner(
                        draft = activeDraft,
                        onResume = {
                            onOpenSession(activeDraft.programId, activeDraft.scheduledId)
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (parkedCompletion != null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                    Spacer(Modifier.height(4.dp))
                    ParkedSessionBanner(
                        parked = parkedCompletion,
                        onRestore = { onRestoreParked(parkedCompletion) },
                        onDiscard = { onDiscardParked(parkedCompletion) },
                    )
                    if (parkedError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(parkedError, style = Hf.type.bodySm, color = Hf.colors.alert)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
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
                // A DRAFT program has no materialized sessions yet — activating
                // it materializes the schedule and marks it ACTIVE, which is what
                // makes a workout runnable from the "This week" strip below.
                if (program.status == ProgramStatus.DRAFT) {
                    Spacer(Modifier.height(12.dp))
                    ActivateButton(label = "Activate program", onClick = onActivate)
                } else if (program.status == ProgramStatus.ACTIVE && thisWeek.isEmpty()) {
                    // Active but nothing scheduled this week — let the user
                    // re-materialize (e.g. after an edit) to refill the schedule.
                    Spacer(Modifier.height(12.dp))
                    ActivateButton(label = "Re-materialize sessions", onClick = onActivate)
                }
                // IMPL-STAB G1: a 422 activation carries the actionable issue list
                // — surface it inline instead of a generic failure.
                if (activationIssues.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ActivationIssues(activationIssues)
                }
                // IMPL-STAB G3: logging used to be reachable only from "This week".
                // Offer a "log a past session" entry whenever earlier materialized
                // sessions exist, so missed/back-dated sessions are loggable too.
                if (hasPastSessions) {
                    Spacer(Modifier.height(12.dp))
                    LogPastSessionRow(onClick = onLogPastSession)
                }
                // IMPL-18b / IMPL-STAB G5: refine via the designer chat — for ACTIVE
                // programs (edit-in-place) AND DRAFT programs (revise before/at
                // activation; the commit re-activates them forward).
                if (program.status == ProgramStatus.ACTIVE || program.status == ProgramStatus.DRAFT) {
                    Spacer(Modifier.height(12.dp))
                    RefineWithAiRow(status = program.status, onClick = onRefineWithAi)
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
                    ThisWeekStrip(
                        scheduled = thisWeek,
                        today = today,
                        // One session at a time: hide Start while a draft is in
                        // flight (the resume banner is the way back in).
                        canStart = activeDraft == null,
                        onStartSession = { session ->
                            onOpenSession(program.programId, session.scheduledId)
                        },
                        // Re-open a completed session to review what was logged.
                        onReviewSession = { session ->
                            onOpenSession(program.programId, session.scheduledId)
                        },
                    )
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
                    onOpenWorkout = onOpenWorkout,
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
private fun ActivateButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.accent, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Hf.type.bodyMd, color = Hf.colors.textInverse)
    }
}

@Composable
private fun RefineWithAiRow(status: ProgramStatus, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .background(Hf.colors.accentBg, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Refine with AI",
                style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                color = Hf.colors.accentDim,
            )
            Text(
                // A DRAFT hasn't started, so there's nothing to "keep"; an ACTIVE
                // program freezes completed sessions and revises forward (18b).
                if (status == ProgramStatus.DRAFT) {
                    "Describe changes — the assistant revises this draft."
                } else {
                    "Revise from today forward — completed sessions are kept."
                },
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = "Refine program with AI",
            tint = Hf.colors.accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** IMPL-STAB G1 — the validator issues from a failed activation, shown inline. */
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
            Text(
                "• $issue",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
        }
    }
}

/** IMPL-STAB G3 — entry point into the past-session picker. */
@Composable
private fun LogPastSessionRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.HistoryEdu,
            contentDescription = null,
            tint = Hf.colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Past workouts",
                style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            Text(
                "Review a finished workout, or log a missed one.",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = "Past workouts",
            tint = Hf.colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** IMPL-STAB G4 — edit the program's title/description (web parity). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProgramSheet(
    initialTitle: String,
    initialDescription: String,
    saving: Boolean,
    error: String?,
    onSave: (title: String, description: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Hf.colors.canvas) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit program", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Text(error, style = Hf.type.bodySm, color = Hf.colors.alert)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Hf.colors.accent, RoundedCornerShape(10.dp))
                    .clickable(enabled = !saving) {
                        onSave(title, description)
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (saving) "Saving…" else "Save changes",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textInverse,
                )
            }
        }
    }
}

/**
 * IMPL-STAB G3 — pick an earlier materialized session to log. The backend only
 * logs against an existing scheduled session, so this lists the program's past
 * sessions (date + label + status); tapping one opens the session logger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastSessionsSheet(
    sessions: List<ScheduledWorkout>,
    onPick: (scheduledId: String) -> Unit,
    onDelete: (scheduledId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // A logged day pending the delete (revert-to-planned) confirmation.
    var pendingDelete by remember { mutableStateOf<ScheduledWorkout?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Hf.colors.canvas) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Past workouts", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
            if (sessions.isEmpty()) {
                Text(
                    "No earlier sessions yet.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            } else {
                sessions.forEach { session ->
                    // A logged outcome (completed or skipped) can be deleted —
                    // reverted to planned; a still-planned day has nothing to remove.
                    val isLogged = session.status == ScheduledStatus.COMPLETED ||
                        session.status == ScheduledStatus.SKIPPED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
                            .clickable { onPick(session.scheduledId) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                session.dayLabel,
                                style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                                color = Hf.colors.textPrimary,
                            )
                            CapsLabel(
                                "${session.date} · ${session.status.name.lowercase()}",
                                color = Hf.colors.textTertiary,
                            )
                        }
                        if (isLogged) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete logged ${session.dayLabel}",
                                tint = Hf.colors.alert,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { pendingDelete = session }
                                    .padding(7.dp),
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "Log ${session.dayLabel}",
                            tint = Hf.colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { session ->
        ConfirmDialog(
            title = "Delete this workout?",
            message = "The logged result for ${session.dayLabel} (${session.date}) will be removed " +
                "and the day goes back to planned. You can run it again later.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            destructive = true,
            onConfirm = {
                onDelete(session.scheduledId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun PhaseRow(
    phase: ProgramPhase,
    isFirst: Boolean,
    isLast: Boolean,
    onOpenWorkout: (phaseId: String, dayId: String) -> Unit,
) {
    // The phase header is a tappable expander: tapping it reveals the phase's
    // workout rows (each of which opens the workout detail). Default open for
    // the first phase and any active phase so content is visible up-front.
    var expanded by remember(phase.phaseId) {
        mutableStateOf(isFirst || phase.status == ProgramPhaseStatus.ACTIVE)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        PhaseSpineNode(status = phase.status, isFirst = isFirst, isLast = isLast)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 18.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        phase.title,
                        style = Hf.type.headingMd.copy(fontSize = 14.sp),
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(5.dp))
                    PhaseMeta(phase)
                }
                Spacer(Modifier.width(8.dp))
                PhaseStatusPill(phase.status)
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse phase" else "Expand phase",
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val days = phase.days.sortedBy { it.orderIndex }
                    if (days.isEmpty()) {
                        Text(
                            "No workouts in this phase yet.",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    } else {
                        days.forEach { day ->
                            WorkoutDayRow(
                                day = day,
                                onOpen = { onOpenWorkout(phase.phaseId, day.dayId) },
                            )
                        }
                    }
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
                today = LocalDate.parse("2026-06-03"),
            ),
            onBack = {},
            onOpenGoal = {},
            onOpenWorkout = { _, _, _ -> },
            onOpenSession = { _, _ -> },
            onRetry = {},
        )
    }
}
