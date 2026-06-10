package com.gte619n.healthfitness.feature.workouts.session

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers.RestTimer
import com.gte619n.healthfitness.domain.workouts.program.BlockTypeLabels
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.R
import com.gte619n.healthfitness.feature.workouts.program.ProgramFixtures
import com.gte619n.healthfitness.feature.workouts.program.prescriptionSummary
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.input.EditableNumber
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun WorkoutSessionRoute(
    onClose: () -> Unit,
    viewModel: WorkoutSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val restTimer by viewModel.restTimer.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }

    // ADR-0012 D6: WorkoutSessionService's shade notification (timer / rest
    // countdown) needs the API 33+ POST_NOTIFICATIONS grant — the foreground
    // service itself runs fine without it. Ask once when the logger opens
    // (same idiom as the nutrition Capture screen's CAMERA request); a denial
    // is non-blocking, the session just runs without a shade entry.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* best-effort: nothing in the logger gates on the grant */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    WorkoutSessionScreen(
        state = state,
        restTimer = restTimer,
        onBack = onClose,
        onToggleSet = viewModel::toggleSet,
        onEditSet = viewModel::editSet,
        onDismissRest = viewModel::dismissRest,
        onRequestFinish = viewModel::requestFinish,
        onRequestSkip = viewModel::requestSkip,
        onRequestDiscard = viewModel::requestDiscard,
        onConfirmFinish = viewModel::confirmFinish,
        onConfirmSkip = viewModel::confirmSkip,
        onConfirmDiscard = viewModel::confirmDiscard,
        onDismissPrompt = viewModel::dismissPrompt,
    )
}

@Composable
fun WorkoutSessionScreen(
    state: WorkoutSessionUiState,
    restTimer: RestTimer?,
    onBack: () -> Unit,
    onToggleSet: (PrescriptionKey, Int) -> Unit,
    onEditSet: (PrescriptionKey, Int, LoggedSet) -> Unit,
    onDismissRest: () -> Unit,
    onRequestFinish: () -> Unit,
    onRequestSkip: () -> Unit,
    onRequestDiscard: () -> Unit,
    onConfirmFinish: () -> Unit,
    onConfirmSkip: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissPrompt: () -> Unit,
) {
    // One-second ticker driving the elapsed header and the rest countdown.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    val draft = state.draft
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = draft?.scheduled?.dayLabel?.ifBlank { null }
                ?: stringResource(R.string.workout_session_title),
            subtitle = draft?.let {
                stringResource(
                    R.string.workout_session_elapsed,
                    elapsedLabel(Duration.between(it.startedAt, now).seconds),
                )
            },
            onBack = onBack,
        )
        when {
            draft == null && state.loading -> LoadingState(Modifier.fillMaxSize())
            draft == null && state.error != null -> ErrorState(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
            )
            draft != null -> SessionBody(
                draft = draft,
                restTimer = restTimer,
                now = now,
                error = state.error,
                onToggleSet = onToggleSet,
                onEditSet = onEditSet,
                onDismissRest = onDismissRest,
                onRequestFinish = onRequestFinish,
                onRequestSkip = onRequestSkip,
                onRequestDiscard = onRequestDiscard,
            )
        }
    }

    when (state.prompt) {
        SessionPrompt.FINISH_SUMMARY -> draft?.let {
            FinishSummaryDialog(
                draft = it,
                now = now,
                onConfirm = onConfirmFinish,
                onDismiss = onDismissPrompt,
            )
        }
        SessionPrompt.SKIP -> ConfirmDialog(
            title = stringResource(R.string.workout_session_skip_title),
            message = stringResource(R.string.workout_session_skip_message),
            confirmLabel = stringResource(R.string.workout_session_skip),
            dismissLabel = stringResource(R.string.workout_session_cancel),
            onConfirm = onConfirmSkip,
            onDismiss = onDismissPrompt,
        )
        SessionPrompt.DISCARD -> ConfirmDialog(
            title = stringResource(R.string.workout_session_discard_title),
            message = stringResource(R.string.workout_session_discard_message),
            confirmLabel = stringResource(R.string.workout_session_discard),
            dismissLabel = stringResource(R.string.workout_session_cancel),
            destructive = true,
            onConfirm = onConfirmDiscard,
            onDismiss = onDismissPrompt,
        )
        null -> Unit
    }
}

@Composable
private fun SessionBody(
    draft: WorkoutSessionDraft,
    restTimer: RestTimer?,
    now: Instant,
    error: String?,
    onToggleSet: (PrescriptionKey, Int) -> Unit,
    onEditSet: (PrescriptionKey, Int, LoggedSet) -> Unit,
    onDismissRest: () -> Unit,
    onRequestFinish: () -> Unit,
    onRequestSkip: () -> Unit,
    onRequestDiscard: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (restTimer != null && restTimer.isRunning(now)) {
            RestTimerBar(restTimer = restTimer, now = now, onDismiss = onDismissRest)
        }
        if (error != null) {
            Text(
                error,
                style = Hf.type.bodySm,
                color = Hf.colors.alert,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }
        val day = draft.scheduled.session
        if (day == null || day.blocks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.workout_session_empty_title),
                description = stringResource(R.string.workout_session_empty_description),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            ) {
                day.blocks.sortedBy { it.orderIndex }.forEach { block ->
                    item(key = "block-${block.blockId}") {
                        Spacer(Modifier.height(8.dp))
                        SectionTitle(text = BlockTypeLabels.label(block.type), compact = true)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(
                        block.prescriptions.sortedBy { it.orderIndex },
                        key = { "${block.blockId}-${it.orderIndex}" },
                    ) { prescription ->
                        val key = PrescriptionKey(block.blockId, prescription.orderIndex)
                        PrescriptionCard(
                            prescription = prescription,
                            logged = draft.logged[key].orEmpty(),
                            onToggleSet = { index -> onToggleSet(key, index) },
                            onEditSet = { index, set -> onEditSet(key, index, set) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        SessionActionsBar(
            onFinish = onRequestFinish,
            onSkip = onRequestSkip,
            onDiscard = onRequestDiscard,
        )
    }
}

@Composable
private fun RestTimerBar(restTimer: RestTimer, now: Instant, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.accentBg)
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Timer,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(
                R.string.workout_session_rest_remaining,
                restCountdownLabel(restTimer.remainingSeconds(now)),
            ),
            style = Hf.type.monoMd,
            color = Hf.colors.accentDim,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(
                stringResource(R.string.workout_session_rest_skip),
                style = Hf.type.bodySm,
                color = Hf.colors.accent,
            )
        }
    }
}

/**
 * One prescription: exercise name, the "3 × 8–10 @ RPE 8 · rest 90s" target,
 * and a check-off row per set with editable weight / reps / optional RPE.
 */
@Composable
private fun PrescriptionCard(
    prescription: Prescription,
    logged: List<LoggedSet>,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            prescription.exercise?.name ?: prescription.exerciseId,
            style = Hf.type.headingMd.copy(fontSize = 14.sp),
            color = Hf.colors.textPrimary,
        )
        val target = prescriptionSummary(prescription)
        if (target.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(target, style = Hf.type.monoSm, color = Hf.colors.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        SetHeaderRow()
        val totalRows = maxOf(prescription.sets ?: 1, logged.size)
        repeat(totalRows) { index ->
            SetRow(
                index = index,
                set = logged.getOrNull(index),
                // Only the next unlogged row is checkable, so sets stay ordered.
                canToggle = index <= logged.size,
                onToggle = { onToggleSet(index) },
                onEdit = { set -> onEditSet(index, set) },
            )
        }
        if (logged.size >= totalRows) {
            TextButton(onClick = { onToggleSet(logged.size) }) {
                Text(
                    stringResource(R.string.workout_session_add_set),
                    style = Hf.type.bodySm,
                    color = Hf.colors.accent,
                )
            }
        }
    }
}

@Composable
private fun SetHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(32.dp))
        CapsLabel(
            stringResource(R.string.workout_session_set_header),
            modifier = Modifier.width(32.dp),
        )
        CapsLabel(
            stringResource(R.string.workout_session_weight_header),
            modifier = Modifier.weight(1f),
        )
        CapsLabel(
            stringResource(R.string.workout_session_reps_header),
            modifier = Modifier.weight(1f),
        )
        CapsLabel(
            stringResource(R.string.workout_session_rpe_header),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SetRow(
    index: Int,
    set: LoggedSet?,
    canToggle: Boolean,
    onToggle: () -> Unit,
    onEdit: (LoggedSet) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (set != null) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Outlined.RadioButtonUnchecked
            },
            contentDescription = stringResource(
                if (set != null) R.string.workout_session_uncheck_set
                else R.string.workout_session_check_set,
                index + 1,
            ),
            tint = when {
                set != null -> Hf.colors.accent
                canToggle -> Hf.colors.textTertiary
                else -> Hf.colors.textQuaternary
            },
            modifier = Modifier
                .size(22.dp)
                .then(if (canToggle) Modifier.clickable { onToggle() } else Modifier),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "${index + 1}",
            style = Hf.type.monoSm,
            color = Hf.colors.textTertiary,
            modifier = Modifier.width(32.dp),
        )
        if (set != null) {
            EditableNumber(
                value = set.weightLbs,
                onCommit = { onEdit(set.copy(weightLbs = it)) },
                modifier = Modifier.weight(1f),
                decimals = 1,
            )
            EditableNumber(
                value = set.reps?.toDouble(),
                onCommit = { onEdit(set.copy(reps = it?.toInt())) },
                modifier = Modifier.weight(1f),
                decimals = 0,
            )
            EditableNumber(
                value = set.rpe,
                onCommit = { onEdit(set.copy(rpe = it)) },
                modifier = Modifier.weight(1f),
                decimals = 1,
            )
        } else {
            Text(
                stringResource(R.string.workout_session_set_pending),
                style = Hf.type.bodySm,
                color = Hf.colors.textQuaternary,
                modifier = Modifier.weight(3f).padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun SessionActionsBar(
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onDiscard) {
            Text(
                stringResource(R.string.workout_session_discard),
                style = Hf.type.bodyMd,
                color = Hf.colors.alert,
            )
        }
        TextButton(onClick = onSkip) {
            Text(
                stringResource(R.string.workout_session_skip),
                style = Hf.type.bodyMd,
                color = Hf.colors.textSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onFinish,
            colors = ButtonDefaults.buttonColors(containerColor = Hf.colors.accent),
        ) {
            Text(
                stringResource(R.string.workout_session_finish),
                style = Hf.type.bodyMd,
                color = Hf.colors.textInverse,
            )
        }
    }
}

/** "Finish → summary → complete": total sets, exercises, and elapsed time. */
@Composable
private fun FinishSummaryDialog(
    draft: WorkoutSessionDraft,
    now: Instant,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (loggedExercises, totalExercises) = loggedExerciseCounts(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.workout_session_finish_title),
                style = Hf.type.headingMd,
                color = Hf.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryRow(
                    label = stringResource(R.string.workout_session_summary_elapsed),
                    value = elapsedLabel(Duration.between(draft.startedAt, now).seconds),
                )
                SummaryRow(
                    label = stringResource(R.string.workout_session_summary_sets),
                    value = pluralStringResource(
                        R.plurals.workout_session_sets_logged,
                        draft.totalLoggedSets,
                        draft.totalLoggedSets,
                    ),
                )
                SummaryRow(
                    label = stringResource(R.string.workout_session_summary_exercises),
                    value = "$loggedExercises / $totalExercises",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.workout_session_finish_confirm),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.workout_session_cancel),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textTertiary,
                )
            }
        },
        containerColor = Hf.colors.surface,
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
        Text(value, style = Hf.type.monoMd, color = Hf.colors.textPrimary)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 900)
@Composable
private fun WorkoutSessionPreview() {
    HealthFitnessTheme {
        WorkoutSessionScreen(
            state = WorkoutSessionUiState(loading = false, draft = ProgramFixtures.activeDraft),
            restTimer = null,
            onBack = {},
            onToggleSet = { _, _ -> },
            onEditSet = { _, _, _ -> },
            onDismissRest = {},
            onRequestFinish = {},
            onRequestSkip = {},
            onRequestDiscard = {},
            onConfirmFinish = {},
            onConfirmSkip = {},
            onConfirmDiscard = {},
            onDismissPrompt = {},
        )
    }
}
