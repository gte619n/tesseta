package com.gte619n.healthfitness.feature.workouts.session

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
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
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.input.EditableNumber
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * IMPL-COACH: dwell per looped demo frame in the in-player demo strip. Slow on
 * purpose — frames should read like a guided demo, not a flickering GIF — and
 * paired with a soft [DEMO_FRAME_CROSSFADE_MILLIS] fade between them.
 */
private const val DEMO_FRAME_LOOP_MILLIS = 10_000L
private const val DEMO_FRAME_CROSSFADE_MILLIS = 1_000

/** At/above this screen width the coach page is centered and width-capped. */
private const val EXPANDED_WIDTH_DP = 600

@Composable
fun WorkoutSessionRoute(
    onClose: () -> Unit,
    viewModel: WorkoutSessionViewModel = hiltViewModel(),
    audioViewModel: CoachAudioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val restTimer by viewModel.restTimer.collectAsStateWithLifecycle()
    val voiceEnabled by audioViewModel.voiceAnnouncements.collectAsStateWithLifecycle()
    val announcer = rememberCoachAnnouncer()
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
        voiceEnabled = voiceEnabled,
        announce = announcer::speak,
        onBack = onClose,
        onToggleSet = viewModel::toggleSet,
        onEditSet = viewModel::editSet,
        onLogTimed = viewModel::logTimedSet,
        onLogSet = viewModel::logSet,
        onDismissRest = viewModel::dismissRest,
        onRequestFinish = viewModel::requestFinish,
        onRequestSkip = viewModel::requestSkip,
        onRequestDiscard = viewModel::requestDiscard,
        onConfirmFinish = viewModel::confirmFinish,
        onConfirmSkip = viewModel::confirmSkip,
        onConfirmDiscard = viewModel::confirmDiscard,
        onDismissPrompt = viewModel::dismissPrompt,
        onDismissCompleted = viewModel::dismissCompleted,
    )
}

@Composable
fun WorkoutSessionScreen(
    state: WorkoutSessionUiState,
    restTimer: RestTimer?,
    voiceEnabled: Boolean = false,
    announce: (String) -> Unit = {},
    onBack: () -> Unit,
    onToggleSet: (PrescriptionKey, Int) -> Unit,
    onEditSet: (PrescriptionKey, Int, LoggedSet) -> Unit,
    onLogTimed: (PrescriptionKey, Int) -> Unit,
    onLogSet: (PrescriptionKey, LoggedSet) -> Unit,
    onDismissRest: () -> Unit,
    onRequestFinish: () -> Unit,
    onRequestSkip: () -> Unit,
    onRequestDiscard: () -> Unit,
    onConfirmFinish: () -> Unit,
    onConfirmSkip: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissPrompt: () -> Unit,
    onDismissCompleted: () -> Unit = {},
) {
    // One-second ticker driving the elapsed header, the rest countdown, and the
    // hold-timer count-up.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    // Coach (one exercise at a time) vs. the whole-workout reference list.
    var overview by rememberSaveable { mutableStateOf(false) }

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
            trailing = if (draft?.scheduled?.session?.blocks?.isNotEmpty() == true) {
                {
                    IconButton(onClick = { overview = !overview }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.List,
                            contentDescription = stringResource(R.string.workout_session_overview),
                            tint = if (overview) Hf.colors.accent else Hf.colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            } else {
                null
            },
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
                overview = overview,
                voiceEnabled = voiceEnabled,
                announce = announce,
                onShowOverview = { overview = it },
                onToggleSet = onToggleSet,
                onEditSet = onEditSet,
                onLogTimed = onLogTimed,
                onLogSet = onLogSet,
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

    // IMPL-COACH: after a successful finish, the recap summary sits over the
    // retained draft snapshot until the user dismisses it (which pops the route).
    if (state.completed) {
        draft?.let {
            CompletionSummaryDialog(
                draft = it,
                now = now,
                recap = state.recap,
                recapLoading = state.recapLoading,
                onDone = onDismissCompleted,
            )
        }
    }
}

@Composable
private fun SessionBody(
    draft: WorkoutSessionDraft,
    restTimer: RestTimer?,
    now: Instant,
    error: String?,
    overview: Boolean,
    voiceEnabled: Boolean,
    announce: (String) -> Unit,
    onShowOverview: (Boolean) -> Unit,
    onToggleSet: (PrescriptionKey, Int) -> Unit,
    onEditSet: (PrescriptionKey, Int, LoggedSet) -> Unit,
    onLogTimed: (PrescriptionKey, Int) -> Unit,
    onLogSet: (PrescriptionKey, LoggedSet) -> Unit,
    onDismissRest: () -> Unit,
    onRequestFinish: () -> Unit,
    onRequestSkip: () -> Unit,
    onRequestDiscard: () -> Unit,
) {
    val steps = remember(draft) { draft.sessionSteps() }
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
        if (steps.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.workout_session_empty_title),
                description = stringResource(R.string.workout_session_empty_description),
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        val pagerState = rememberPagerState(
            initialPage = remember(draft.scheduledId) { draft.firstIncompleteStepIndex() },
            pageCount = { steps.size },
        )
        val scope = rememberCoroutineScope()

        // PR2: speak the exercise + target as the coach settles on it (not while
        // paging through the reference list, and only when settled — no cue per
        // pixel of a drag).
        if (voiceEnabled && !overview) {
            val settledPage = pagerState.currentPage.takeIf { !pagerState.isScrollInProgress }
            LaunchedEffect(settledPage) {
                settledPage?.let { steps.getOrNull(it)?.prescription }
                    ?.let { coachAnnouncement(it) }
                    ?.let(announce)
            }
        }

        if (overview) {
            OverviewList(
                steps = steps,
                logged = draft.logged,
                modifier = Modifier.weight(1f),
                onOpenStep = { index ->
                    onShowOverview(false)
                    scope.launch { pagerState.scrollToPage(index) }
                },
            )
            // The whole-session management lever lives in the reference view.
            SessionActionsBar(
                onFinish = onRequestFinish,
                onSkip = onRequestSkip,
                onDiscard = onRequestDiscard,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 12.dp,
            ) { page ->
                val step = steps[page]
                ExercisePage(
                    step = step,
                    logged = draft.logged[step.key].orEmpty(),
                    now = now,
                    onToggleSet = { index -> onToggleSet(step.key, index) },
                    onEditSet = { index, set -> onEditSet(step.key, index, set) },
                    onLogTimed = { seconds -> onLogTimed(step.key, seconds) },
                    onLogSet = { set -> onLogSet(step.key, set) },
                )
            }
            CoachActionsBar(
                page = pagerState.currentPage,
                count = steps.size,
                onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                onFinish = onRequestFinish,
                onAbandon = onRequestDiscard,
            )
        }
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
 * The focused coach page for a single exercise: the looped demo (hero), the
 * "3 × 8–10 @ RPE 8 · rest 90s" target, and the set rows — weight/reps for a
 * strength move, a hold timer for a timed one.
 */
@Composable
private fun ExercisePage(
    step: SessionStep,
    logged: List<LoggedSet>,
    now: Instant,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogTimed: (Int) -> Unit,
    onLogSet: (LoggedSet) -> Unit,
) {
    val prescription = step.prescription
    // On unfolded / tablet widths, keep the page (and its demo image) from
    // stretching edge-to-edge — a centered, narrower card reads better.
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.widthIn(max = 560.dp) else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SectionTitle(text = BlockTypeLabels.label(step.block.type), compact = true)
            Spacer(Modifier.height(8.dp))
            Text(
                prescription.exercise?.name ?: prescription.exerciseId,
                style = Hf.type.headingLg.copy(fontSize = 24.sp),
                color = Hf.colors.textPrimary,
            )
            val target = prescriptionSummary(prescription)
            if (target.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(target, style = Hf.type.monoMd.copy(fontSize = 16.sp), color = Hf.colors.textSecondary)
            }
            DemoStrip(prescription.exercise)
            Spacer(Modifier.height(16.dp))

            if (prescription.isTimed) {
                TimedSets(
                    prescription = prescription,
                    logged = logged,
                    now = now,
                    onToggleSet = onToggleSet,
                    onEditSet = onEditSet,
                    onLogTimed = onLogTimed,
                )
            } else {
                RepSets(
                    prescription = prescription,
                    logged = logged,
                    onToggleSet = onToggleSet,
                    onEditSet = onEditSet,
                    onLogSet = onLogSet,
                )
            }
        }
    }
}

// ---- rep-based sets ----

@Composable
private fun RepSets(
    prescription: Prescription,
    logged: List<LoggedSet>,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogSet: (LoggedSet) -> Unit,
) {
    SetsHeader(addEnabled = canAddSet(prescription, logged), onAdd = { onToggleSet(logged.size) }) {
        CapsLabel(stringResource(R.string.workout_session_weight_header), modifier = Modifier.weight(1f))
        CapsLabel(stringResource(R.string.workout_session_reps_header), modifier = Modifier.weight(1f))
    }
    val exerciseName = prescription.exercise?.name ?: prescription.exerciseId
    val nextIndex = logged.size
    // Prefill for the next, not-yet-logged set: weight/reps carried from the last
    // logged set, else the designed target.
    val carriedWeight = logged.lastOrNull()?.weightLbs ?: prescription.targetWeightLbs
    val targetReps = logged.lastOrNull()?.reps ?: prescription.repsMax ?: prescription.repsMin
    // Pending values for the next set. Editing these only stages the numbers —
    // the set is logged ONLY when the user taps the circle (re-keyed per set so
    // each new row carries forward the previous load/reps).
    var pendingWeight by remember(prescription.exerciseId, nextIndex) { mutableStateOf(carriedWeight) }
    var pendingReps by remember(prescription.exerciseId, nextIndex) { mutableStateOf(targetReps) }

    val totalRows = maxOf(prescription.sets ?: 1, logged.size)
    repeat(totalRows) { index ->
        val set = logged.getOrNull(index)
        val isNext = set == null && index == logged.size
        SetRowFrame(
            index = index,
            logged = set != null,
            // Only the next unlogged row is checkable, so sets stay ordered.
            canToggle = index <= logged.size,
            onToggle = {
                when {
                    set != null -> onToggleSet(index) // un-log
                    isNext -> onLogSet(LoggedSet(weightLbs = pendingWeight, reps = pendingReps))
                }
            },
        ) {
            when {
                set != null -> {
                    WeightStat(
                        value = set.weightLbs,
                        exerciseName = exerciseName,
                        onPick = { onEditSet(index, set.copy(weightLbs = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    RepsStat(
                        value = set.reps,
                        exerciseName = exerciseName,
                        onPick = { onEditSet(index, set.copy(reps = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                isNext -> {
                    WeightStat(
                        value = pendingWeight,
                        exerciseName = exerciseName,
                        onPick = { pendingWeight = it },
                        modifier = Modifier.weight(1f),
                    )
                    RepsStat(
                        value = pendingReps,
                        exerciseName = exerciseName,
                        onPick = { pendingReps = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> PendingHint(weight = 2)
            }
        }
    }
}

/** A big, tappable weight readout that opens the scroll-wheel picker (no keyboard). */
@Composable
private fun WeightStat(
    value: Double?,
    exerciseName: String,
    onPick: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    StatValue(text = formatWeight(value), modifier = modifier, onClick = { show = true })
    if (show) {
        WeightPickerDialog(
            exerciseName = exerciseName,
            initialLbs = value,
            onConfirm = { onPick(it); show = false },
            onDismiss = { show = false },
        )
    }
}

/** A big, tappable reps readout that opens the stepper popup (no keyboard). */
@Composable
private fun RepsStat(
    value: Int?,
    exerciseName: String,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    StatValue(text = value?.toString() ?: "—", modifier = modifier, onClick = { show = true })
    if (show) {
        RepsPickerDialog(
            exerciseName = exerciseName,
            initial = value ?: 0,
            onConfirm = { onPick(it); show = false },
            onDismiss = { show = false },
        )
    }
}

/** The shared big-number tap target used for both weight and reps. */
@Composable
private fun StatValue(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = Hf.type.monoMd.copy(fontSize = 28.sp),
            color = if (text == "—") Hf.colors.textQuaternary else Hf.colors.textPrimary,
        )
    }
}

/** Whole numbers render without a trailing ".0"; fractional loads keep one place. */
private fun formatWeight(value: Double?): String = when {
    value == null -> "—"
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> "%.1f".format(value)
}

// ---- timed sets (stretch / mobility holds) ----

@Composable
private fun TimedSets(
    prescription: Prescription,
    logged: List<LoggedSet>,
    now: Instant,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogTimed: (Int) -> Unit,
) {
    SetsHeader(addEnabled = canAddSet(prescription, logged), onAdd = { onToggleSet(logged.size) }) {
        CapsLabel(stringResource(R.string.workout_session_time_header), modifier = Modifier.weight(2f))
    }
    val totalRows = maxOf(prescription.sets ?: 1, logged.size)
    repeat(totalRows) { index ->
        val set = logged.getOrNull(index)
        SetRowFrame(
            index = index,
            logged = set != null,
            canToggle = index <= logged.size,
            onToggle = { onToggleSet(index) },
        ) {
            if (set != null) {
                EditableNumber(
                    value = set.durationSeconds?.toDouble(),
                    onCommit = { onEditSet(index, set.copy(durationSeconds = it?.toInt())) },
                    modifier = Modifier.weight(2f),
                    decimals = 0,
                    suffix = "s",
                )
            } else {
                PendingHint(weight = 2)
            }
        }
    }
    // Guided "press start, hold, press stop" for the next unlogged set.
    if (logged.size < totalRows) {
        HoldTimer(
            targetSeconds = prescription.durationSeconds,
            now = now,
            onLog = onLogTimed,
        )
    }
}

/**
 * A count-up hold timer: tap to start, tap again to log the elapsed seconds as
 * the next timed set. Resets whenever the exercise changes (it is `remember`ed
 * inside the per-page composable).
 */
@Composable
private fun HoldTimer(targetSeconds: Int?, now: Instant, onLog: (Int) -> Unit) {
    var startedAt by remember { mutableStateOf<Instant?>(null) }
    val running = startedAt != null
    val elapsed = startedAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0L
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = {
            val start = startedAt
            if (start == null) {
                startedAt = Instant.now()
            } else {
                onLog(Duration.between(start, Instant.now()).seconds.coerceAtLeast(0).toInt())
                startedAt = null
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) Hf.colors.alert else Hf.colors.accent,
        ),
    ) {
        Icon(
            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Hf.colors.textInverse,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (running) {
                stringResource(R.string.workout_session_hold_stop, restCountdownLabel(elapsed))
            } else {
                stringResource(R.string.workout_session_hold_start)
            },
            style = Hf.type.bodyMd,
            color = Hf.colors.textInverse,
        )
    }
    if (!running && targetSeconds != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.workout_session_hold_target, restCountdownLabel(targetSeconds.toLong())),
            style = Hf.type.bodySm,
            color = Hf.colors.textTertiary,
        )
    }
}

// ---- shared set-row scaffolding ----

@Composable
private fun SetsHeader(
    addEnabled: Boolean,
    onAdd: () -> Unit,
    columns: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(48.dp))
        CapsLabel(
            stringResource(R.string.workout_session_set_header),
            modifier = Modifier.width(28.dp),
        )
        columns()
        // Adding sets mid-workout is rare — a small, quiet affordance.
        IconButton(onClick = onAdd, enabled = addEnabled, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.workout_session_add_set),
                tint = if (addEnabled) Hf.colors.accent else Hf.colors.textQuaternary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SetRowFrame(
    index: Int,
    logged: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    fields: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 48dp touch target around the check circle.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(if (canToggle) Modifier.clickable { onToggle() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (logged) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = stringResource(
                    if (logged) R.string.workout_session_uncheck_set else R.string.workout_session_check_set,
                    index + 1,
                ),
                tint = when {
                    logged -> Hf.colors.accent
                    canToggle -> Hf.colors.textTertiary
                    else -> Hf.colors.textQuaternary
                },
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            "${index + 1}",
            style = Hf.type.monoMd,
            color = Hf.colors.textTertiary,
            modifier = Modifier.width(28.dp),
        )
        fields()
    }
}

@Composable
private fun RowScope.PendingHint(weight: Int) {
    Text(
        stringResource(R.string.workout_session_set_pending),
        style = Hf.type.bodySm,
        color = Hf.colors.textQuaternary,
        modifier = Modifier.weight(weight.toFloat()).padding(horizontal = 8.dp),
    )
}

/** True once every prescribed set is logged — adding beyond the plan is the only time the +icon lights up. */
private fun canAddSet(prescription: Prescription, logged: List<LoggedSet>): Boolean =
    logged.size >= maxOf(prescription.sets ?: 1, logged.size)

// ---- demo strip ----

/**
 * IMPL-COACH: the current exercise's demo frames, slowly cross-faded inline as
 * the visual hero of the coach page (reusing the IMPL-19 frame plan). Renders
 * nothing when the exercise has no usable frames.
 */
@Composable
private fun DemoStrip(exercise: ExerciseSummary?) {
    val frames = remember(exercise) {
        exercise?.demoFrames
            ?.withIndex()
            ?.sortedWith(compareBy({ it.value.order }, { it.index }))
            ?.mapNotNull { indexed ->
                indexed.value.imageUrl?.let { url -> url to indexed.value.label }
            }
            .orEmpty()
    }
    if (frames.isEmpty()) return

    var index by remember(frames) { mutableStateOf(0) }
    if (frames.size > 1) {
        LaunchedEffect(frames) {
            while (true) {
                delay(DEMO_FRAME_LOOP_MILLIS)
                index = (index + 1) % frames.size
            }
        }
    }
    val safeIndex = index.coerceIn(0, frames.lastIndex)

    Spacer(Modifier.height(12.dp))
    Box(
        modifier = Modifier
            // Portrait frame, and ContentScale.Fit below, so the whole figure is
            // shown top-to-bottom instead of being cropped (the demos are tall).
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clip(RoundedCornerShape(10.dp))
            .background(Hf.colors.canvasMuted),
        contentAlignment = Alignment.BottomStart,
    ) {
        Crossfade(
            targetState = safeIndex,
            animationSpec = tween(DEMO_FRAME_CROSSFADE_MILLIS),
            label = "demo-frame",
        ) { i ->
            HfAsyncImage(
                model = frames[i].first,
                contentDescription = exercise?.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val label = frames[safeIndex].second
        if (label.isNotBlank()) {
            Text(
                label,
                style = Hf.type.bodySm,
                color = Hf.colors.textPrimary,
                modifier = Modifier
                    .padding(8.dp)
                    .background(Hf.colors.canvas.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ---- reference overview ----

/** The whole-workout reference list: scan progress and jump to any exercise. */
@Composable
private fun OverviewList(
    steps: List<SessionStep>,
    logged: Map<PrescriptionKey, List<LoggedSet>>,
    modifier: Modifier = Modifier,
    onOpenStep: (Int) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
    ) {
        steps.forEachIndexed { index, step ->
            val firstOfBlock = index == 0 || steps[index - 1].block.blockId != step.block.blockId
            item(key = "overview-${step.key.blockId}-${step.key.orderIndex}") {
                if (firstOfBlock) {
                    Spacer(Modifier.height(8.dp))
                    SectionTitle(text = BlockTypeLabels.label(step.block.type), compact = true)
                    Spacer(Modifier.height(6.dp))
                }
                OverviewRow(
                    step = step,
                    loggedCount = logged[step.key]?.size ?: 0,
                    onClick = { onOpenStep(index) },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun OverviewRow(step: SessionStep, loggedCount: Int, onClick: () -> Unit) {
    val target = step.prescription.sets ?: 1
    val done = loggedCount >= target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) Hf.colors.accent else Hf.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                step.prescription.exercise?.name ?: step.prescription.exerciseId,
                style = Hf.type.headingMd.copy(fontSize = 14.sp),
                color = Hf.colors.textPrimary,
            )
            val summary = prescriptionSummary(step.prescription)
            if (summary.isNotBlank()) {
                Text(summary, style = Hf.type.monoSm, color = Hf.colors.textSecondary)
            }
        }
        Text(
            "$loggedCount/$target",
            style = Hf.type.monoSm,
            color = if (done) Hf.colors.accent else Hf.colors.textTertiary,
        )
    }
}

// ---- bottom action bars ----

/** Focused-mode bar: abandon, where you are in the workout, advance, or finish. */
@Composable
private fun CoachActionsBar(
    page: Int,
    count: Int,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onAbandon: () -> Unit,
) {
    val last = page >= count - 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Exit-without-saving lives here so it's reachable on every page, not
        // only from the overview list.
        TextButton(onClick = onAbandon, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(
                stringResource(R.string.workout_session_discard),
                style = Hf.type.bodyMd,
                color = Hf.colors.alert,
            )
        }
        Text(
            "${page + 1} / $count",
            style = Hf.type.monoSm,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.weight(1f))
        if (!last) {
            TextButton(onClick = onFinish) {
                Text(
                    stringResource(R.string.workout_session_finish),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textSecondary,
                )
            }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = Hf.colors.accent),
            ) {
                Text(
                    stringResource(R.string.workout_session_next),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textInverse,
                )
            }
        } else {
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
}

/** Overview-mode bar: manage the whole session (finish / skip / discard). */
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

/**
 * IMPL-COACH: the post-finish summary — the same stats as the pre-confirm
 * dialog plus the best-effort AI coach recap (a spinner-free "writing" line
 * while it loads, the note when it lands, nothing if it never arrives).
 */
@Composable
private fun CompletionSummaryDialog(
    draft: WorkoutSessionDraft,
    now: Instant,
    recap: String?,
    recapLoading: Boolean,
    onDone: () -> Unit,
) {
    val (loggedExercises, totalExercises) = loggedExerciseCounts(draft)
    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                stringResource(R.string.workout_session_complete_title),
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
                when {
                    recapLoading -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.workout_session_recap_loading),
                            style = Hf.type.bodyMd,
                            color = Hf.colors.textTertiary,
                        )
                    }
                    !recap.isNullOrBlank() -> {
                        Spacer(Modifier.height(4.dp))
                        CapsLabel(text = stringResource(R.string.workout_session_recap_label))
                        Spacer(Modifier.height(2.dp))
                        Text(recap, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) {
                Text(
                    stringResource(R.string.workout_session_done),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.accent,
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
            onLogTimed = { _, _ -> },
            onLogSet = { _, _ -> },
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
