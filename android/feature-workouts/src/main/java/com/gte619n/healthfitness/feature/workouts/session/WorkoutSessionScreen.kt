package com.gte619n.healthfitness.feature.workouts.session

import android.Manifest
import android.content.pm.PackageManager
import android.media.ToneGenerator
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
    val playCompletionChime = rememberCompletionChime()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }

    // "Auto complete workout": the final set just landed — chime once (and, if
    // voice is on, say so), then clear the one-shot so a recomposition doesn't
    // replay it. The finish summary is already open (opened by the ViewModel).
    LaunchedEffect(state.autoCompleted) {
        if (state.autoCompleted) {
            playCompletionChime()
            if (voiceEnabled) announcer.speak("Workout complete.")
            viewModel.consumeAutoCompleted()
        }
    }

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
        substituteOptions = state.substituteOptions,
        substituteLoading = state.substituteLoading,
        substituteError = state.substituteError,
        onLoadSubstitutes = viewModel::loadSubstituteOptions,
        onAdjust = viewModel::applyAdjustment,
        isOwner = state.isOwner,
        onFlagFrame = viewModel::flagFrame,
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
    substituteOptions: List<ExerciseSummary> = emptyList(),
    substituteLoading: Boolean = false,
    substituteError: String? = null,
    onLoadSubstitutes: (String) -> Unit = {},
    onAdjust: (PrescriptionKey, PrescriptionAdjustment) -> Unit = { _, _ -> },
    isOwner: Boolean = false,
    onFlagFrame: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onToggleSet: (PrescriptionKey, Int) -> Unit,
    onEditSet: (PrescriptionKey, Int, LoggedSet) -> Unit,
    onLogTimed: (PrescriptionKey, Int) -> Unit,
    onLogSet: (PrescriptionKey, LoggedSet) -> Unit,
    onDismissRest: () -> Unit,
    onRequestFinish: () -> Unit,
    onRequestSkip: () -> Unit,
    onRequestDiscard: () -> Unit,
    onConfirmFinish: (Int?) -> Unit,
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
                val elapsedSeconds =
                    Duration.between(it.startedAt, now).toMillis().coerceAtLeast(0L) / 1000L
                stringResource(R.string.workout_session_elapsed, elapsedLabel(elapsedSeconds))
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
                substituteOptions = substituteOptions,
                substituteLoading = substituteLoading,
                substituteError = substituteError,
                onLoadSubstitutes = onLoadSubstitutes,
                onAdjust = onAdjust,
                isOwner = isOwner,
                onFlagFrame = onFlagFrame,
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
    substituteOptions: List<ExerciseSummary>,
    substituteLoading: Boolean,
    substituteError: String?,
    onLoadSubstitutes: (String) -> Unit,
    onAdjust: (PrescriptionKey, PrescriptionAdjustment) -> Unit,
    isOwner: Boolean,
    onFlagFrame: (String, String) -> Unit,
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
            // Resume where the lifter actually is, not the first gap — a warmup
            // they passed without logging must not drag focus back on resume.
            initialPage = remember(draft.scheduledId) { draft.resumeStepIndex() },
            pageCount = { steps.size },
        )
        val scope = rememberCoroutineScope()

        // The destination page a completed hold hands off to: when a hold
        // auto-completes into another hold, that page's timer runs a "get ready"
        // pre-roll and auto-starts (a hold into a lift just advances, no
        // auto-start). Consumed by the destination page once it picks it up.
        var autoStartStep by remember { mutableStateOf<Int?>(null) }

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
                lastAnnounced != -1 && page != lastAnnounced &&
                // An auto-started hold announces its own "get ready for X" — let
                // the pre-roll speak instead of double-announcing here.
                autoStartStep != page
            ) {
                announceStep(step, draft.logged[step.key].orEmpty(), lastSets, announce)
            }
            lastAnnounced = page
        }
        // When a rest countdown starts, announce its length ("Rest 90 seconds");
        // when it finishes naturally (not skipped), re-announce the current
        // exercise so the user knows what's up. Skipping rest clears the timer,
        // restarting this effect with null and cancelling the pending cue.
        LaunchedEffect(restTimer, voiceEnabled) {
            val timer = restTimer ?: return@LaunchedEffect
            if (!voiceEnabled || overview) return@LaunchedEffect
            val remaining = timer.remainingSeconds(Instant.now())
            // Announce the rest only if it just started — a resume mid-rest
            // (remaining already below the total) stays silent.
            if (remaining >= timer.totalSeconds - 1) {
                announce(restAnnouncement(timer.totalSeconds))
            }
            if (remaining > 0) delay(remaining * 1_000)
            steps.getOrNull(pagerState.currentPage)?.let { step ->
                announceStep(step, draft.logged[step.key].orEmpty(), lastSets, announce)
            }
        }
        // Feature 3: blow the whistle the instant a rep set's rest countdown runs
        // out — the "start your set" cue. Only rep sets start a rest, so any
        // natural expiry is a set start; skipping rest clears the timer and
        // re-keys this effect to null, cancelling the pending blast. Independent
        // of the voice toggle (it's a sound cue, like the coach beep).
        val whistle = rememberWhistle()
        LaunchedEffect(restTimer) {
            val timer = restTimer ?: return@LaunchedEffect
            val remaining = timer.remainingSeconds(Instant.now())
            if (remaining <= 0) return@LaunchedEffect
            delay(remaining * 1_000)
            whistle()
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
                val nextIndex = pagerState.currentPage + 1
                // Hand off to the next page's guided "get ready" pre-roll only
                // when a completed hold flows into another hold; a hold into a
                // lift just advances and waits for the user to log manually.
                autoStartStep = if (step.prescription.isTimed &&
                    steps.getOrNull(nextIndex)?.prescription?.isTimed == true
                ) nextIndex else null
                // Run the scroll on [scope], not this effect's coroutine: the
                // pager flips currentPage at the animation's midpoint, which
                // re-keys this LaunchedEffect and would otherwise cancel the
                // scroll half-way (leaving two half-pages on screen).
                scope.launch { pagerState.animateScrollToPage(nextIndex) }
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
                // clipToBounds so a neighbouring page can never bleed past the
                // screen edge; pageSpacing 0 + no contentPadding means only the
                // current page is ever on screen at rest (swipe is disabled, so
                // pages advance via Next / auto-advance, sliding cleanly).
                modifier = Modifier.weight(1f).clipToBounds(),
                userScrollEnabled = false,
                pageSpacing = 0.dp,
            ) { page ->
                val step = steps[page]
                ExercisePage(
                    step = step,
                    logged = draft.logged[step.key].orEmpty(),
                    lastSets = lastSets,
                    now = now,
                    restTimer = restTimer,
                    voiceEnabled = voiceEnabled,
                    announce = announce,
                    substituteOptions = substituteOptions,
                    substituteLoading = substituteLoading,
                    substituteError = substituteError,
                    onLoadSubstitutes = onLoadSubstitutes,
                    onAdjust = { adjustment -> onAdjust(step.key, adjustment) },
                    isOwner = isOwner,
                    onFlagFrame = onFlagFrame,
                    onToggleSet = { index -> onToggleSet(step.key, index) },
                    onEditSet = { index, set -> onEditSet(step.key, index, set) },
                    onLogTimed = { seconds -> onLogTimed(step.key, seconds) },
                    onLogSet = { set -> onLogSet(step.key, set) },
                    onDismissRest = onDismissRest,
                    autoStart = autoStartStep == page,
                    onAutoStartConsumed = { if (autoStartStep == page) autoStartStep = null },
                )
            }
            CoachActionsBar(
                page = pagerState.currentPage,
                count = steps.size,
                onPrevious = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
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
    voiceEnabled: Boolean,
    announce: (String) -> Unit,
    substituteOptions: List<ExerciseSummary>,
    substituteLoading: Boolean,
    substituteError: String?,
    onLoadSubstitutes: (String) -> Unit,
    onAdjust: (PrescriptionAdjustment) -> Unit,
    isOwner: Boolean,
    onFlagFrame: (String, String) -> Unit,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogTimed: (Int) -> Unit,
    onLogSet: (LoggedSet) -> Unit,
    onDismissRest: () -> Unit,
    autoStart: Boolean = false,
    onAutoStartConsumed: () -> Unit = {},
) {
    val prescription = step.prescription
    var showSwap by remember(step.key) { mutableStateOf(false) }
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
                // The page's own side margin (previously the pager's contentPadding,
                // which caused the next card to peek at the edge).
                .padding(horizontal = 18.dp, vertical = 8.dp),
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
            // #4: swap this movement for a muscle-matched one the current gym can
            // do, and/or adjust its sets/reps — for just this workout or the whole
            // program. Loads ranked options lazily when the picker opens.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SwapHoriz,
                    contentDescription = null,
                    tint = Hf.colors.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.workout_session_swap),
                    style = Hf.type.bodySm,
                    color = Hf.colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showSwap = true; onLoadSubstitutes(prescription.exerciseId) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            // Demo hero; the rest countdown takes it over while resting so the
            // timer is unmissable without losing the exercise context.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DemoStrip(
                    exercise = prescription.exercise,
                    isOwner = isOwner,
                    onFlagFrame = onFlagFrame,
                    modifier = Modifier.fillMaxSize(),
                )
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
                    voiceEnabled = voiceEnabled,
                    announce = announce,
                    autoStart = autoStart,
                    onAutoStartConsumed = onAutoStartConsumed,
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
    if (showSwap) {
        AdjustExerciseDialog(
            prescription = prescription,
            options = substituteOptions,
            loading = substituteLoading,
            error = substituteError,
            onApply = { adjustment ->
                onAdjust(adjustment)
                showSwap = false
            },
            onDismiss = { showSwap = false },
        )
    }
}

/**
 * #4 — the mid-session swap / adjust picker: pick a muscle-matched movement the
 * current gym can do (ranked, searchable) and/or edit the prescribed sets & rep
 * range, applied to just this workout or the whole program. The list filters
 * client-side over the pre-ranked options; loading/empty/error states cover the
 * fetch.
 */
@Composable
private fun AdjustExerciseDialog(
    prescription: Prescription,
    options: List<ExerciseSummary>,
    loading: Boolean,
    error: String?,
    onApply: (PrescriptionAdjustment) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ExerciseSummary?>(null) }
    var setsStr by remember { mutableStateOf(prescription.sets?.toString().orEmpty()) }
    var repsMinStr by remember { mutableStateOf(prescription.repsMin?.toString().orEmpty()) }
    var repsMaxStr by remember { mutableStateOf(prescription.repsMax?.toString().orEmpty()) }
    var applyToProgram by remember { mutableStateOf(false) }

    val currentId = prescription.exerciseId
    val choices = remember(options, currentId, search) {
        val q = search.trim().lowercase()
        options
            .filter { it.exerciseId != currentId }
            .filter {
                q.isBlank() || it.name.lowercase().contains(q) ||
                    it.primaryMuscles.any { m -> m.lowercase().contains(q) }
            }
    }
    val targetsChanged = setsStr != prescription.sets?.toString().orEmpty() ||
        repsMinStr != prescription.repsMin?.toString().orEmpty() ||
        repsMaxStr != prescription.repsMax?.toString().orEmpty()
    val hasChange = picked != null || targetsChanged

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.workout_session_swap_title),
                style = Hf.type.headingMd,
                color = Hf.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.workout_session_swap_search), style = Hf.type.bodyMd)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    loading -> Text(
                        stringResource(R.string.workout_session_swap_loading),
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textTertiary,
                    )
                    error != null -> Text(error, style = Hf.type.bodyMd, color = Hf.colors.alert)
                    choices.isEmpty() -> Text(
                        stringResource(
                            if (search.isBlank()) R.string.workout_session_swap_empty
                            else R.string.workout_session_swap_none,
                        ),
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textTertiary,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(choices, key = { it.exerciseId }) { exercise ->
                            SwapExerciseRow(
                                exercise = exercise,
                                selected = picked?.exerciseId == exercise.exerciseId,
                                onClick = {
                                    picked = if (picked?.exerciseId == exercise.exerciseId) null else exercise
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumberField(setsStr, { setsStr = it },
                        stringResource(R.string.workout_session_adjust_sets), Modifier.weight(1f))
                    NumberField(repsMinStr, { repsMinStr = it },
                        stringResource(R.string.workout_session_adjust_reps_min), Modifier.weight(1f))
                    NumberField(repsMaxStr, { repsMaxStr = it },
                        stringResource(R.string.workout_session_adjust_reps_max), Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScopeToggle(!applyToProgram,
                        stringResource(R.string.workout_session_scope_workout),
                        { applyToProgram = false }, Modifier.weight(1f))
                    ScopeToggle(applyToProgram,
                        stringResource(R.string.workout_session_scope_program),
                        { applyToProgram = true }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        PrescriptionAdjustment(
                            exercise = picked,
                            sets = setsStr.toIntOrNull()?.takeIf { it != prescription.sets },
                            repsMin = repsMinStr.toIntOrNull()?.takeIf { it != prescription.repsMin },
                            repsMax = repsMaxStr.toIntOrNull()?.takeIf { it != prescription.repsMax },
                            applyToProgram = applyToProgram,
                        ),
                    )
                },
                enabled = hasChange,
            ) {
                Text(
                    stringResource(R.string.workout_session_adjust_apply),
                    style = Hf.type.bodyMd,
                    color = if (hasChange) Hf.colors.accent else Hf.colors.textTertiary,
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

/** Compact numeric field for the sets / rep-range edits. */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label, style = Hf.type.bodySm) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Segmented "this workout / whole program" scope choice. */
@Composable
private fun ScopeToggle(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text, style = Hf.type.bodySm)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
        }
    }
}

@Composable
private fun SwapExerciseRow(
    exercise: ExerciseSummary,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                if (selected) 1.5.dp else 0.5.dp,
                if (selected) Hf.colors.accent else Hf.colors.borderDefault,
                RoundedCornerShape(10.dp),
            )
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseThumbnail(
            imageUrl = exerciseImageUrl(exercise),
            contentDescription = exercise.name,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                exercise.name,
                style = Hf.type.headingMd.copy(fontSize = 14.sp),
                color = Hf.colors.textPrimary,
            )
            val muscles = exercise.primaryMuscles.joinToString(", ")
            if (muscles.isNotBlank()) {
                Text(muscles, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
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
                prescription = prescription,
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
                prescription = prescription,
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
    prescription: Prescription,
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
                // Live target-vs-achieved: dips red the moment the staged load
                // drops under the prescription, green once it meets it (#8).
                valueColor = outcomeColor(weightOutcome(prescription, weight)),
                modifier = Modifier.weight(1f),
                onClick = { showWeight = true },
            )
            SetFieldBox(
                label = stringResource(R.string.workout_session_reps_header),
                value = reps?.toString() ?: "—",
                valueColor = outcomeColor(repsOutcome(prescription, reps)),
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
    valueColor: Color = Hf.colors.textPrimary,
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
                color = if (value == "—") Hf.colors.textQuaternary else valueColor,
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
    prescription: Prescription,
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
            color = outcomeColor(weightOutcome(prescription, set.weightLbs)),
            onClick = { showWeight = true },
        )
        Text(" × ", style = Hf.type.monoMd, color = Hf.colors.textTertiary)
        CompletedValue(
            text = set.reps?.toString() ?: "—",
            color = outcomeColor(repsOutcome(prescription, set.reps)),
            onClick = { showReps = true },
        )
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
private fun CompletedValue(
    text: String,
    onClick: () -> Unit,
    color: Color = Hf.colors.textPrimary,
) {
    Text(
        text,
        style = Hf.type.monoMd,
        color = color,
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

/**
 * Target-vs-achieved text colour (#8): green when the logged value met or beat
 * the prescription, red when it fell short, plain text when there's nothing to
 * compare against.
 */
@Composable
private fun outcomeColor(outcome: TargetOutcome): Color = when (outcome) {
    TargetOutcome.HIT -> Hf.colors.good
    TargetOutcome.MISS -> Hf.colors.alert
    TargetOutcome.NEUTRAL -> Hf.colors.textPrimary
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
    voiceEnabled: Boolean,
    announce: (String) -> Unit,
    autoStart: Boolean,
    onAutoStartConsumed: () -> Unit,
    onToggleSet: (Int) -> Unit,
    onEditSet: (Int, LoggedSet) -> Unit,
    onLogTimed: (Int) -> Unit,
) {
    val totalRows = maxOf(prescription.sets ?: 1, logged.size)
    val hasPending = logged.size < totalRows
    // Which pending-set index should auto-start its hold. Seeded from the
    // cross-exercise hand-off (the first set of a hold reached from another
    // completed hold), then advanced by each auto-completed hold so a multi-set
    // hold chains itself through the same "get ready" pre-roll.
    var autoStartSet by remember { mutableStateOf(-1) }
    LaunchedEffect(autoStart) { if (autoStart) autoStartSet = logged.size }
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
            val pendingIndex = logged.size
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Hf.colors.surface)
                    .border(1.5.dp, Hf.colors.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                CapsLabel(
                    stringResource(R.string.workout_session_set_of, pendingIndex + 1, totalRows),
                    color = Hf.colors.accent,
                )
                // Fresh timer per pending set so its running/pre-roll state can't
                // bleed across sets.
                key(pendingIndex) {
                    HoldTimer(
                        prescription = prescription,
                        now = now,
                        voiceEnabled = voiceEnabled,
                        announce = announce,
                        autoStart = autoStartSet == pendingIndex,
                        onAutoStartConsumed = {
                            autoStartSet = -1
                            onAutoStartConsumed()
                        },
                        onLog = onLogTimed,
                        // Reaching the target auto-logs; queue the next set of this
                        // same hold to auto-start (the last set instead advances the
                        // page, where the section unmounts and this is a no-op).
                        onAutoComplete = { autoStartSet = pendingIndex + 1 },
                    )
                }
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
 * A goal-aware hold timer for a timed set (stretch / mobility). It counts up to
 * the prescribed hold, beeping (and, with voice on, speaking) at the halfway
 * mark and at ten seconds to go, and — reaching the target — auto-logs the hold
 * and marks the set complete rather than waiting for a tap. The user can still
 * start a hold by hand and stop it early, but a finished hold no longer needs a
 * second tap.
 *
 * Before the hold's clock runs there's a "get ready" pre-roll that counts down
 * the prescribed rest between sets ([Prescription.restSeconds], falling back to
 * [GET_READY_SECONDS] when none is set) — so the rest is folded into the guided
 * flow rather than left out. The pre-roll runs on the cross-exercise hand-off
 * ([autoStart]) and between a hold's own sets; tapping "Start now" skips it. A
 * [rememberWhistle] blast plays the instant the clock starts (pre-roll → hold,
 * or a manual start), the "go" cue.
 *
 * Both the pre-roll and the running hold can be paused and reset ("sometimes
 * you're just not ready"): pause freezes the clock, reset returns the pre-roll to
 * the full rest or the hold to idle. State is kept as an accumulated-seconds base
 * plus a wall-clock anchor in `rememberSaveable`, so a config change /
 * backgrounding doesn't lose a paused or in-progress timer.
 */
@Composable
private fun HoldTimer(
    prescription: Prescription,
    now: Instant,
    voiceEnabled: Boolean,
    announce: (String) -> Unit,
    autoStart: Boolean,
    onAutoStartConsumed: () -> Unit,
    onLog: (Int) -> Unit,
    onAutoComplete: () -> Unit,
) {
    val targetSeconds = prescription.durationSeconds
    val target = targetSeconds ?: 0
    // The pre-roll counts down the prescribed rest between sets; fall back to a
    // short fixed lead-in when the prescription sets none.
    val prerollTotal = (prescription.restSeconds?.takeIf { it > 0 } ?: GET_READY_SECONDS.toInt()).toLong()

    // Pre-roll and hold both use an accumulated-seconds base + a running anchor
    // (epoch millis, null when paused) so pause/reset survive recomposition.
    var prerollBase by rememberSaveable { mutableStateOf(0L) }
    var prerollAnchor by rememberSaveable { mutableStateOf<Long?>(null) }
    var prerollArmed by rememberSaveable { mutableStateOf(false) }
    var holdBase by rememberSaveable { mutableStateOf(0L) }
    var holdAnchor by rememberSaveable { mutableStateOf<Long?>(null) }
    var holdArmed by rememberSaveable { mutableStateOf(false) }

    fun secondsSince(anchorMillis: Long): Long =
        Duration.between(Instant.ofEpochMilli(anchorMillis), now).seconds.coerceAtLeast(0L)

    val prerollElapsed = prerollBase + (prerollAnchor?.let { secondsSince(it) } ?: 0L)
    val prerollRemaining = (prerollTotal - prerollElapsed).coerceAtLeast(0L)
    val prerollRunning = prerollArmed && prerollAnchor != null
    val prerollPaused = prerollArmed && prerollAnchor == null

    val elapsed = holdBase + (holdAnchor?.let { secondsSince(it) } ?: 0L)
    val holdRunning = holdArmed && holdAnchor != null
    val holdPaused = holdArmed && holdAnchor == null

    // One-shot cue flags for the current hold; reset when a fresh hold starts.
    var firedHalf by remember { mutableStateOf(false) }
    var firedTen by remember { mutableStateOf(false) }
    var firedDone by remember { mutableStateOf(false) }
    val beep = rememberCoachBeep()
    val whistle = rememberWhistle()

    fun nowMillis() = Instant.now().toEpochMilli()

    fun startPreroll() {
        prerollArmed = true
        prerollBase = 0L
        prerollAnchor = nowMillis()
    }

    fun startHold() {
        prerollArmed = false
        prerollAnchor = null
        firedHalf = false; firedTen = false; firedDone = false
        holdArmed = true
        holdBase = 0L
        holdAnchor = nowMillis()
        // Feature 3: the "go" whistle fires right as the clock starts.
        whistle()
    }

    // Enter the rest pre-roll when handed off from a completed hold, then consume
    // the signal so a later recomposition / return to this page can't re-arm it.
    // Announces the upcoming hold once, up front.
    LaunchedEffect(autoStart) {
        if (autoStart && !holdArmed && !prerollArmed) {
            startPreroll()
            if (voiceEnabled) getReadyAnnouncement(prescription)?.let(announce)
            onAutoStartConsumed()
        }
    }

    // The pre-roll running out auto-starts the hold (a paused pre-roll waits).
    LaunchedEffect(prerollRemaining, prerollRunning) {
        if (prerollRunning && prerollRemaining <= 0L) startHold()
    }

    // Fire the halfway / ten-seconds-left cues as the count-up crosses each mark,
    // and auto-log the moment the target is reached. Runs each tick (elapsed
    // changes every second) while the hold is running; the flags stop any cue
    // repeating.
    LaunchedEffect(elapsed, holdRunning) {
        if (!holdRunning || target <= 0) return@LaunchedEffect
        if (!firedHalf && target >= HALF_CUE_MIN_TARGET && elapsed >= target / 2 && elapsed < target - 10) {
            firedHalf = true
            beep(ToneGenerator.TONE_PROP_BEEP)
            if (voiceEnabled) announce("Halfway")
        }
        if (!firedTen && target >= TEN_CUE_MIN_TARGET && elapsed >= target - 10 && elapsed < target) {
            firedTen = true
            beep(ToneGenerator.TONE_PROP_BEEP)
            if (voiceEnabled) announce("10 seconds left")
        }
        if (!firedDone && elapsed >= target) {
            firedDone = true
            beep(ToneGenerator.TONE_PROP_ACK)
            if (voiceEnabled) announce("Time's up")
            // Mark the set complete automatically and hand the block on.
            onLog(target)
            onAutoComplete()
            holdArmed = false
            holdAnchor = null
        }
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = {
            when {
                // During the pre-roll (running or paused), skip straight to the hold.
                prerollArmed -> startHold()
                // Tap while holding (or paused mid-hold) logs early with whatever
                // time is on the clock; no hand-off to the next set's pre-roll.
                holdArmed -> {
                    onLog(elapsed.toInt())
                    holdArmed = false
                    holdAnchor = null
                    firedHalf = false; firedTen = false; firedDone = false
                }
                else -> startHold()
            }
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (holdArmed) Hf.colors.alert else Hf.colors.accent,
        ),
    ) {
        Icon(
            if (holdArmed) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Hf.colors.textInverse,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                prerollArmed -> stringResource(R.string.workout_session_get_ready_start_now)
                holdArmed -> stringResource(R.string.workout_session_hold_stop, restCountdownLabel(elapsed))
                else -> stringResource(R.string.workout_session_hold_start)
            },
            style = Hf.type.bodyMd,
            color = Hf.colors.textInverse,
        )
    }
    // Pause/Resume + Reset controls, shown whenever a pre-roll or hold is armed.
    if (prerollArmed || holdArmed) {
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val paused = prerollPaused || holdPaused
            HoldControlButton(
                label = stringResource(
                    if (paused) R.string.workout_session_hold_resume else R.string.workout_session_hold_pause,
                ),
                icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                modifier = Modifier.weight(1f),
                onClick = {
                    when {
                        prerollRunning -> { prerollBase = prerollElapsed; prerollAnchor = null }
                        prerollPaused -> prerollAnchor = nowMillis()
                        holdRunning -> { holdBase = elapsed; holdAnchor = null }
                        holdPaused -> holdAnchor = nowMillis()
                    }
                },
            )
            HoldControlButton(
                label = stringResource(R.string.workout_session_hold_reset),
                icon = Icons.Filled.Refresh,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (prerollArmed) {
                        // Restart the rest countdown from the top; keep running/paused.
                        prerollBase = 0L
                        if (prerollAnchor != null) prerollAnchor = nowMillis()
                    } else {
                        // Reset the hold all the way back to idle ("not ready yet").
                        holdArmed = false
                        holdAnchor = null
                        holdBase = 0L
                        firedHalf = false; firedTen = false; firedDone = false
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            prerollRunning -> stringResource(
                R.string.workout_session_get_ready_countdown,
                restCountdownLabel(prerollRemaining),
            )
            prerollPaused -> stringResource(
                R.string.workout_session_get_ready_paused,
                restCountdownLabel(prerollRemaining),
            )
            holdPaused -> stringResource(
                R.string.workout_session_hold_paused,
                restCountdownLabel(elapsed),
            )
            !holdArmed && targetSeconds != null -> stringResource(
                R.string.workout_session_hold_target,
                restCountdownLabel(targetSeconds.toLong()),
            )
            else -> ""
        },
        style = Hf.type.bodySm,
        color = if (prerollArmed) Hf.colors.accent else Hf.colors.textTertiary,
    )
}

/** One secondary control (Pause/Resume or Reset) on the hold timer. */
@Composable
private fun HoldControlButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Hf.colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Hf.type.bodySm, color = Hf.colors.accent)
    }
}

/** Fallback pre-roll length when a timed prescription sets no rest between sets. */
private const val GET_READY_SECONDS = 10L

/** Only announce "halfway" for holds this long or longer (shorter ones just get the finish cue). */
private const val HALF_CUE_MIN_TARGET = 30
/** Only announce "10 seconds left" for holds this long or longer. */
private const val TEN_CUE_MIN_TARGET = 25

// ---- demo strip ----

/**
 * IMPL-COACH: the current exercise's demo frames, slowly cross-faded inline as
 * the visual hero of the coach page (reusing the IMPL-19 frame plan). Renders
 * nothing when the exercise has no usable frames.
 */
@Composable
private fun DemoStrip(
    exercise: ExerciseSummary?,
    isOwner: Boolean = false,
    onFlagFrame: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Keep each frame's stable key alongside its url/label so the owner-only
    // "flag as bad" control (#9) can reference the exact frame it's showing.
    val frames = remember(exercise) {
        exercise?.demoFrames
            ?.withIndex()
            ?.sortedWith(compareBy({ it.value.order }, { it.index }))
            ?.mapNotNull { indexed ->
                indexed.value.imageUrl?.let { url -> DemoFrameView(url, indexed.value.label, indexed.value.key) }
            }
            .orEmpty()
    }
    var index by remember(frames) { mutableStateOf(0) }
    var flagging by remember(exercise) { mutableStateOf(false) }
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
                    model = frames[i].url,
                    contentDescription = exercise?.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val label = frames[safeIndex].label
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
            // #9: owner-only "flag this demo image as bad" — tags the exact frame
            // on screen so it re-enters media review. Hidden from ordinary users.
            if (isOwner) {
                IconButton(
                    onClick = { flagging = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(Hf.colors.canvas.copy(alpha = 0.85f), RoundedCornerShape(8.dp)),
                ) {
                    Icon(
                        Icons.Outlined.Flag,
                        contentDescription = stringResource(R.string.workout_session_flag_image),
                        tint = Hf.colors.alert,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (flagging) {
                val frameKey = frames[safeIndex].key
                val exerciseId = exercise?.exerciseId
                ConfirmDialog(
                    title = stringResource(R.string.workout_session_flag_title),
                    message = stringResource(R.string.workout_session_flag_message),
                    confirmLabel = stringResource(R.string.workout_session_flag_confirm),
                    dismissLabel = stringResource(R.string.workout_session_cancel),
                    destructive = true,
                    onConfirm = {
                        if (exerciseId != null) onFlagFrame(exerciseId, frameKey)
                        flagging = false
                    },
                    onDismiss = { flagging = false },
                )
            }
        }
    }
}

/** A demo frame ready to render: its image [url], [label], and stable plan [key] (#9). */
private data class DemoFrameView(val url: String, val label: String, val key: String)

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

/**
 * Focused-mode bar. Only the two moves you make constantly — step Back and Next
 * — sit out on the bar (with direction icons); the whole-workout levers (Finish,
 * Abandon) are tucked into an overflow "More" menu so they can't be hit by
 * accident and don't crowd the navigation.
 */
@Composable
private fun CoachActionsBar(
    page: Int,
    count: Int,
    onPrevious: () -> Unit,
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
        // Step back to a prior exercise to fix or undo an earlier set — the
        // pager is otherwise forward-only (swipe is disabled). Hidden on the
        // first page where there's nowhere to go back to.
        if (page > 0) {
            TextButton(onClick = onPrevious, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = Hf.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    stringResource(R.string.workout_session_previous),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textSecondary,
                )
            }
        }
        Text(
            "${page + 1} / $count",
            style = Hf.type.monoSm,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.weight(1f))
        CoachMoreMenu(onFinish = onFinish, onAbandon = onAbandon)
        // Next auto-advances after the last set is logged, so this is the manual
        // skip-ahead; hidden on the last exercise (finish via the More menu).
        if (!last) {
            TextButton(onClick = onNext, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(
                    stringResource(R.string.workout_session_next),
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textSecondary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Hf.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The overflow menu holding the whole-workout actions: Finish and Abandon. */
@Composable
private fun CoachMoreMenu(onFinish: () -> Unit, onAbandon: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.workout_session_more),
                tint = Hf.colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Hf.colors.surface,
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.workout_session_finish),
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textPrimary,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Hf.colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = { expanded = false; onFinish() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.workout_session_discard),
                        style = Hf.type.bodyMd,
                        color = Hf.colors.alert,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        tint = Hf.colors.alert,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = { expanded = false; onAbandon() },
            )
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

/** "Finish → summary → complete": total sets, exercises, elapsed time, and a mood check. */
@Composable
private fun FinishSummaryDialog(
    draft: WorkoutSessionDraft,
    now: Instant,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val (loggedExercises, totalExercises) = loggedExerciseCounts(draft)
    // The post-workout mood check (1..5), captured for trending. Optional — the
    // lifter can finish without picking one (feeling stays null).
    var feeling by rememberSaveable { mutableStateOf<Int?>(null) }
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
                Spacer(Modifier.height(6.dp))
                FeelingPicker(selected = feeling, onSelect = { feeling = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(feeling) }) {
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

/**
 * The post-workout mood check: five emoji faces mapped to a 1..5 score, captured
 * on finish for future trending. Optional — nothing is preselected, and a tap
 * toggles (tapping the chosen face again clears it back to null).
 */
@Composable
private fun FeelingPicker(selected: Int?, onSelect: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CapsLabel(stringResource(R.string.workout_session_feeling_prompt))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FEELING_FACES.forEach { (value, emoji) ->
                val isSelected = selected == value
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (isSelected) Hf.colors.accentBg else Hf.colors.surface,
                            RoundedCornerShape(24.dp),
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) Hf.colors.accent else Hf.colors.borderDefault,
                            shape = RoundedCornerShape(24.dp),
                        )
                        .clickable { onSelect(if (isSelected) null else value) },
                ) {
                    Text(
                        emoji,
                        style = Hf.type.bodyLg.copy(fontSize = 26.sp),
                        // Dim the unchosen faces so the selection reads at a glance.
                        modifier = Modifier.alpha(if (selected == null || isSelected) 1f else 0.45f),
                    )
                }
            }
        }
    }
}

/** The 1..5 mood scale: worst → best, paired with its face. */
private val FEELING_FACES = listOf(
    1 to "😫",
    2 to "🙁",
    3 to "😐",
    4 to "🙂",
    5 to "😄",
)

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
