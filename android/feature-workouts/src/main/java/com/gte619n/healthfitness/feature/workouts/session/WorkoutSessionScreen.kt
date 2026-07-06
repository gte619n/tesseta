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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
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
import com.gte619n.healthfitness.feature.workouts.program.ui.ExerciseThumbnail
import com.gte619n.healthfitness.feature.workouts.program.ui.exerciseImageUrl
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

/** Pause after the final set is logged before auto-advancing, so the check lands visibly. */
private const val AUTO_ADVANCE_DELAY_MILLIS = 900L

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
                lastSets = state.lastSets,
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
    lastSets: Map<String, List<LoggedSet>>,
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

        // ---- coach voice cues (PR2, refined) --------------------------------
        // Speak only when the coach *changes* exercise, never on the first
        // settle after (re)entering the composition — coming back into the app
        // or clicking the notification must not re-announce. [lastAnnounced]
        // survives config change / process death via rememberSaveable so a
        // resume stays silent.
        val settledPage = pagerState.currentPage.takeIf { !pagerState.isScrollInProgress }
        var lastAnnounced by rememberSaveable(draft.scheduledId) { mutableStateOf(-1) }
        LaunchedEffect(settledPage, voiceEnabled) {
            val page = settledPage ?: return@LaunchedEffect
            val step = steps.getOrNull(page)
            if (voiceEnabled && !overview && step != null &&
                lastAnnounced != -1 && page != lastAnnounced
            ) {
                announceStep(step, draft.logged[step.key].orEmpty(), lastSets, announce)
            }
            lastAnnounced = page
        }
        // When a rest countdown finishes naturally (not skipped), re-announce the
        // current exercise so the user knows what's up. Skipping rest clears the
        // timer, restarting this effect with null and cancelling the pending cue.
        LaunchedEffect(restTimer, voiceEnabled) {
            val timer = restTimer ?: return@LaunchedEffect
            if (!voiceEnabled || overview) return@LaunchedEffect
            val remaining = timer.remainingSeconds(Instant.now())
            if (remaining > 0) delay(remaining * 1_000)
            steps.getOrNull(pagerState.currentPage)?.let { step ->
                announceStep(step, draft.logged[step.key].orEmpty(), lastSets, announce)
            }
        }

        // ---- auto-advance to the next exercise when all its sets are logged --
        // Fires only when a set is logged *while on this page* (count grew), not
        // on composition or a page switch, so re-entering a finished session
        // doesn't walk itself forward.
        val currentStep = steps.getOrNull(pagerState.currentPage)
        val currentLoggedCount = currentStep?.let { draft.logged[it.key]?.size ?: 0 } ?: 0
        var progress by remember { mutableStateOf(pagerState.currentPage to currentLoggedCount) }
        LaunchedEffect(pagerState.currentPage, currentLoggedCount) {
            val (prevPage, prevCount) = progress
            val justLogged = pagerState.currentPage == prevPage && currentLoggedCount > prevCount
            progress = pagerState.currentPage to currentLoggedCount
            val step = steps.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
            val target = step.prescription.sets ?: 1
            val isLast = pagerState.currentPage >= steps.size - 1
            if (!justLogged || currentLoggedCount < target || isLast) return@LaunchedEffect
            // Let the user see the last set's check land before moving on.
            delay(AUTO_ADVANCE_DELAY_MILLIS)
            if ((draft.logged[step.key]?.size ?: 0) >= target &&
                pagerState.currentPage < steps.size - 1
            ) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }

        if (overview) {
            if (restTimer != null && restTimer.isRunning(now)) {
                RestTimerBar(restTimer = restTimer, now = now, onDismiss = onDismissRest)
            }
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
            // Swipe navigation is disabled (it fired too easily): exercises
            // change only via the Next control, auto-advance, and the overview.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false,
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 12.dp,
            ) { page ->
                val step = steps[page]
                ExercisePage(
                    step = step,
                    logged = draft.logged[step.key].orEmpty(),
                    lastSets = lastSets,
                    now = now,
                    restTimer = restTimer,
                    onToggleSet = { index -> onToggleSet(step.key, index) },
                    onEditSet = { index, set -> onEditSet(step.key, index, set) },
                    onLogTimed = { seconds -> onLogTimed(step.key, seconds) },
                    onLogSet = { set -> onLogSet(step.key, set) },
                    onDismissRest = onDismissRest,
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

/** Speak the exercise + its effective (prefilled) load — shared by both cues. */
private fun announceStep(
    step: SessionStep,
    logged: List<LoggedSet>,
    lastSets: Map<String, List<LoggedSet>>,
    announce: (String) -> Unit,
) {
    val prefill = prefillFor(step.prescription, logged, lastSets)
    coachAnnouncement(step.prescription, weightLbs = prefill.weightLbs, reps = prefill.reps)
        ?.let(announce)
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
 * The focused coach page for a single exercise: the looped demo (hero, with the
 * rest countdown overlaid while resting), the "3 × 8–10 @ RPE 8 · rest 90s"
 * target, and the focused set card — one active set at a time, completed sets
 * collapsed above it and the remaining count below.
 */
@Composable
private fun ExercisePage(
    step: SessionStep,
    logged: List<LoggedSet>,
    lastSets: Map<String, List<LoggedSet>>,
    now: Instant,
    restTimer: RestTimer?,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogTimed: (Int) -> Unit,
    onLogSet: (LoggedSet) -> Unit,
    onDismissRest: () -> Unit,
) {
    val prescription = step.prescription
    // On unfolded / tablet widths, keep the page (and its demo image) from
    // stretching edge-to-edge — a centered, narrower card reads better.
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // No scroll: header (name/details) and footer (sets) stay pinned, and the
        // demo image flexes to fill whatever vertical space is left between them.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (expanded) Modifier.widthIn(max = 560.dp) else Modifier)
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
            Spacer(Modifier.height(12.dp))
            // Demo hero; the rest countdown takes it over while resting so the
            // timer is unmissable without losing the exercise context.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DemoStrip(exercise = prescription.exercise, modifier = Modifier.fillMaxSize())
                if (restTimer != null && restTimer.isRunning(now)) {
                    RestOverlay(
                        restTimer = restTimer,
                        now = now,
                        onDismiss = onDismissRest,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (prescription.isTimed) {
                TimedSetsSection(
                    prescription = prescription,
                    logged = logged,
                    now = now,
                    onToggleSet = onToggleSet,
                    onEditSet = onEditSet,
                    onLogTimed = onLogTimed,
                )
            } else {
                RepSetsSection(
                    prescription = prescription,
                    logged = logged,
                    lastSets = lastSets,
                    onToggleSet = onToggleSet,
                    onEditSet = onEditSet,
                    onLogSet = onLogSet,
                )
            }
        }
    }
}

/** The rest countdown, large and centered, laid over the demo hero while resting. */
@Composable
private fun RestOverlay(
    restTimer: RestTimer,
    now: Instant,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Hf.colors.canvas.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CapsLabel(stringResource(R.string.workout_session_rest_label), color = Hf.colors.accent)
            Text(
                restCountdownLabel(restTimer.remainingSeconds(now)),
                style = Hf.type.monoLg.copy(fontSize = 72.sp),
                color = Hf.colors.textPrimary,
            )
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Hf.colors.accent),
            ) {
                Text(
                    stringResource(R.string.workout_session_rest_skip),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textInverse,
                )
            }
        }
    }
}

// ---- rep-based sets: focused current-set card ----

@Composable
private fun RepSetsSection(
    prescription: Prescription,
    logged: List<LoggedSet>,
    lastSets: Map<String, List<LoggedSet>>,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogSet: (LoggedSet) -> Unit,
) {
    val exerciseName = prescription.exercise?.name ?: prescription.exerciseId
    val totalRows = maxOf(prescription.sets ?: 1, logged.size)
    val hasPending = logged.size < totalRows
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Completed sets collapse to a compact, still-editable strip.
        logged.forEachIndexed { index, set ->
            CompletedRepRow(
                index = index,
                set = set,
                exerciseName = exerciseName,
                canUndo = index == logged.lastIndex,
                onEditWeight = { onEditSet(index, set.copy(weightLbs = it)) },
                onEditReps = { onEditSet(index, set.copy(reps = it)) },
                onUndo = { onToggleSet(index) },
            )
        }
        if (hasPending) {
            ActiveRepCard(
                setNumber = logged.size + 1,
                totalSets = totalRows,
                prefill = prefillFor(prescription, logged, lastSets),
                exerciseName = exerciseName,
                onLog = { weight, reps -> onLogSet(LoggedSet(weightLbs = weight, reps = reps)) },
            )
            UpcomingHint(remaining = totalRows - (logged.size + 1))
        } else {
            AllSetsDoneRow(total = totalRows)
        }
    }
}

/**
 * The hero of the sets area: the one set the user is on. Big, obviously-editable
 * weight/reps fields and a full-width primary "Log set" that records the set and
 * lets the card advance to the next one.
 */
@Composable
private fun ActiveRepCard(
    setNumber: Int,
    totalSets: Int,
    prefill: SetPrefill,
    exerciseName: String,
    onLog: (Double?, Int?) -> Unit,
) {
    // Staged values for this set; re-keyed per set (and when the prefill lands)
    // so each new row carries the previous load/reps forward.
    var weight by remember(exerciseName, setNumber, prefill.weightLbs) {
        mutableStateOf(prefill.weightLbs)
    }
    var reps by remember(exerciseName, setNumber, prefill.reps) { mutableStateOf(prefill.reps) }
    var showWeight by remember { mutableStateOf(false) }
    var showReps by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Hf.colors.surface)
            .border(1.5.dp, Hf.colors.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CapsLabel(
            stringResource(R.string.workout_session_set_of, setNumber, totalSets),
            color = Hf.colors.accent,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SetFieldBox(
                label = stringResource(R.string.workout_session_weight_header),
                value = formatWeight(weight),
                modifier = Modifier.weight(1f),
                onClick = { showWeight = true },
            )
            SetFieldBox(
                label = stringResource(R.string.workout_session_reps_header),
                value = reps?.toString() ?: "—",
                modifier = Modifier.weight(1f),
                onClick = { showReps = true },
            )
        }
        Button(
            onClick = { onLog(weight, reps) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Hf.colors.accent),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Hf.colors.textInverse,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.workout_session_log_set, setNumber),
                style = Hf.type.bodyMd,
                color = Hf.colors.textInverse,
            )
        }
    }
    if (showWeight) {
        WeightPickerDialog(
            exerciseName = exerciseName,
            initialLbs = weight,
            onConfirm = { weight = it; showWeight = false },
            onDismiss = { showWeight = false },
        )
    }
    if (showReps) {
        RepsPickerDialog(
            exerciseName = exerciseName,
            initial = reps ?: 0,
            onConfirm = { reps = it; showReps = false },
            onDismiss = { showReps = false },
        )
    }
}

/**
 * A labelled, obviously-tappable field (muted fill, border, edit glyph) that
 * opens the weight/reps picker — makes it clear the numbers are editable.
 */
@Composable
private fun SetFieldBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier) {
        CapsLabel(label, color = Hf.colors.textTertiary)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Hf.colors.canvasMuted)
                .border(1.dp, Hf.colors.borderStrong, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                value,
                style = Hf.type.monoLg.copy(fontSize = 32.sp),
                color = if (value == "—") Hf.colors.textQuaternary else Hf.colors.textPrimary,
            )
            Icon(
                Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.workout_session_edit),
                tint = Hf.colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A logged rep set, one compact line — weight/reps stay tappable to fix, last is undoable. */
@Composable
private fun CompletedRepRow(
    index: Int,
    set: LoggedSet,
    exerciseName: String,
    canUndo: Boolean,
    onEditWeight: (Double?) -> Unit,
    onEditReps: (Int) -> Unit,
    onUndo: () -> Unit,
) {
    var showWeight by remember { mutableStateOf(false) }
    var showReps by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "${index + 1}",
            style = Hf.type.monoSm,
            color = Hf.colors.textTertiary,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        CompletedValue(
            text = "${formatWeight(set.weightLbs)} ${stringResource(R.string.workout_session_weight_header)}",
            onClick = { showWeight = true },
        )
        Text(" × ", style = Hf.type.monoMd, color = Hf.colors.textTertiary)
        CompletedValue(text = set.reps?.toString() ?: "—", onClick = { showReps = true })
        Spacer(Modifier.weight(1f))
        if (canUndo) {
            IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.workout_session_uncheck_set, index + 1),
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (showWeight) {
        WeightPickerDialog(
            exerciseName = exerciseName,
            initialLbs = set.weightLbs,
            onConfirm = { onEditWeight(it); showWeight = false },
            onDismiss = { showWeight = false },
        )
    }
    if (showReps) {
        RepsPickerDialog(
            exerciseName = exerciseName,
            initial = set.reps ?: 0,
            onConfirm = { onEditReps(it); showReps = false },
            onDismiss = { showReps = false },
        )
    }
}

@Composable
private fun CompletedValue(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = Hf.type.monoMd,
        color = Hf.colors.textPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** "2 sets to go" hint below the active card — nothing when the active set is the last. */
@Composable
private fun UpcomingHint(remaining: Int) {
    if (remaining <= 0) return
    Text(
        pluralStringResource(R.plurals.workout_session_sets_to_go, remaining, remaining),
        style = Hf.type.bodySm,
        color = Hf.colors.textQuaternary,
        modifier = Modifier.padding(start = 30.dp, top = 2.dp),
    )
}

/** All prescribed sets logged — the coach is about to move on. */
@Composable
private fun AllSetsDoneRow(total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(R.plurals.workout_session_all_sets_done, total, total),
            style = Hf.type.bodyMd,
            color = Hf.colors.textSecondary,
        )
    }
}

/** Whole numbers render without a trailing ".0"; fractional loads keep one place. */
private fun formatWeight(value: Double?): String = when {
    value == null -> "—"
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> "%.1f".format(value)
}

// ---- timed sets (stretch / mobility holds): focused hold card ----

@Composable
private fun TimedSetsSection(
    prescription: Prescription,
    logged: List<LoggedSet>,
    now: Instant,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogTimed: (Int) -> Unit,
) {
    val totalRows = maxOf(prescription.sets ?: 1, logged.size)
    val hasPending = logged.size < totalRows
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        logged.forEachIndexed { index, set ->
            CompletedTimedRow(
                index = index,
                set = set,
                canUndo = index == logged.lastIndex,
                onEdit = { onEditSet(index, set.copy(durationSeconds = it?.toInt())) },
                onUndo = { onToggleSet(index) },
            )
        }
        if (hasPending) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Hf.colors.surface)
                    .border(1.5.dp, Hf.colors.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                CapsLabel(
                    stringResource(R.string.workout_session_set_of, logged.size + 1, totalRows),
                    color = Hf.colors.accent,
                )
                HoldTimer(targetSeconds = prescription.durationSeconds, now = now, onLog = onLogTimed)
            }
            UpcomingHint(remaining = totalRows - (logged.size + 1))
        } else {
            AllSetsDoneRow(total = totalRows)
        }
    }
}

/** A logged timed set, one compact line — duration stays editable, last is undoable. */
@Composable
private fun CompletedTimedRow(
    index: Int,
    set: LoggedSet,
    canUndo: Boolean,
    onEdit: (Double?) -> Unit,
    onUndo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "${index + 1}",
            style = Hf.type.monoSm,
            color = Hf.colors.textTertiary,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        EditableNumber(
            value = set.durationSeconds?.toDouble(),
            onCommit = onEdit,
            modifier = Modifier.weight(1f),
            decimals = 0,
            suffix = "s",
        )
        if (canUndo) {
            IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.workout_session_uncheck_set, index + 1),
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
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
        modifier = Modifier.fillMaxWidth().height(52.dp),
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

// ---- demo strip ----

/**
 * IMPL-COACH: the current exercise's demo frames, slowly cross-faded inline as
 * the visual hero of the coach page (reusing the IMPL-19 frame plan). Renders
 * nothing when the exercise has no usable frames.
 */
@Composable
private fun DemoStrip(exercise: ExerciseSummary?, modifier: Modifier = Modifier) {
    val frames = remember(exercise) {
        exercise?.demoFrames
            ?.withIndex()
            ?.sortedWith(compareBy({ it.value.order }, { it.index }))
            ?.mapNotNull { indexed ->
                indexed.value.imageUrl?.let { url -> url to indexed.value.label }
            }
            .orEmpty()
    }
    var index by remember(frames) { mutableStateOf(0) }
    if (frames.size > 1) {
        LaunchedEffect(frames) {
            while (true) {
                delay(DEMO_FRAME_LOOP_MILLIS)
                index = (index + 1) % frames.size
            }
        }
    }

    Box(
        // Fills the space ExercisePage gives it; ContentScale.Crop makes the
        // figure cover the box (no letterbox bars) however tall or short it is.
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Hf.colors.canvasMuted),
        contentAlignment = Alignment.Center,
    ) {
        if (frames.isEmpty()) {
            Icon(
                Icons.Outlined.FitnessCenter,
                contentDescription = null,
                tint = Hf.colors.textQuaternary,
                modifier = Modifier.size(48.dp),
            )
        } else {
            val safeIndex = index.coerceIn(0, frames.lastIndex)
            Crossfade(
                targetState = safeIndex,
                animationSpec = tween(DEMO_FRAME_CROSSFADE_MILLIS),
                label = "demo-frame",
                modifier = Modifier.fillMaxSize(),
            ) { i ->
                HfAsyncImage(
                    model = frames[i].first,
                    contentDescription = exercise?.name,
                    contentScale = ContentScale.Crop,
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
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Hf.colors.canvas.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
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
        ExerciseThumbnail(
            imageUrl = exerciseImageUrl(step.prescription.exercise),
            contentDescription = step.prescription.exercise?.name,
            size = 44.dp,
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
            // Secondary now: logging the last set auto-advances, so Next is only
            // a manual skip-ahead — a quiet control, not the filled primary.
            TextButton(onClick = onNext) {
                Text(
                    stringResource(R.string.workout_session_next),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.accent,
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
