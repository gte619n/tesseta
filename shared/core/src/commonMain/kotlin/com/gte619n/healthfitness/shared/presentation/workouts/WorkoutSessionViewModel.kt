package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.WorkoutSessionRepository
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.Prescription
import com.gte619n.healthfitness.shared.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * IMPL-IOS-01 Phase 3 Wave D(ii) — the SHARED active-workout ViewModel (KMP
 * `commonMain`), the port of Android
 * `feature-workouts/.../session/WorkoutSessionViewModel.kt` +
 * `data.workouts.session.WorkoutSessionTimers`.
 *
 * Port notes / deltas from the Android original:
 *  - `androidx.lifecycle.ViewModel` (KMP artifact, D2); the repository is a plain
 *    constructor param wired by the platform (Hilt on Android, a factory on iOS).
 *  - `SavedStateHandle` nav args become plain constructor params.
 *  - `java.time.Instant`/`Duration` → kotlinx-datetime + integer-second math.
 *  - THE REST TIMER is a SINGLE self-ticking source (see [RestTimerHolder]).
 *    Android split the countdown across `WorkoutSessionTimers.rest` (an
 *    end-anchored value) AND a separate on-screen `now` clock in the composable —
 *    the rest-timer-dual-state-gate bug (D10): the overlay could vanish while the
 *    notification kept ticking because two states disagreed. Here there is ONE
 *    `StateFlow<RestTimerState?>` driven by one coroutine ticker. The overlay, the
 *    beep trigger, AND the Live Activity all read this exact flow — they cannot
 *    diverge. No second clock exists.
 *  - The set-logging / draft-persistence contract is preserved 1:1 (every edit
 *    → Room via the repo; finish/skip/discard route through the outbox).
 *
 * Owner-gating (#9), gym-swap (#4), progression re-grounding (#3), and the
 * timed-hold get-ready pre-roll from the Android VM are OUT OF SCOPE for the iOS
 * session MVP (the sibling designer/gym vertical owns swap; get-ready folds into
 * the same timer source when ported). Everything the live logger needs — draft
 * set logging, single-source rest, RIR/effort capture, auto-complete, recap — is
 * here.
 */

/** Which confirmation the logger is showing (finish summary, skip, discard). */
enum class SessionPrompt { FINISH_SUMMARY, SKIP, DISCARD }

/**
 * What the running countdown is: a between-sets [REST], a pre-hold [GET_READY]
 * pre-roll, or a live isometric [HOLD] (#275) — a countdown of the hold's target
 * seconds whose on-screen count-*up* is `totalSeconds - remaining`.
 */
enum class RestKind { REST, GET_READY, HOLD }

/**
 * ONE countdown state — the single source the overlay, the beep, and the Live
 * Activity all render. [remainingSeconds] is recomputed by the VM's own ticker
 * each second, so consumers never need a second clock (the dual-state bug). The
 * timer is process-scoped (ADR-0012 D6): it lives in memory and is intentionally
 * NOT persisted with the draft — a stale countdown is worthless on resume.
 */
data class RestTimerState(
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val kind: RestKind = RestKind.REST,
    val isPaused: Boolean = false,
) {
    /** True while the countdown still has time left and isn't frozen. */
    val isRunning: Boolean get() = !isPaused && remainingSeconds > 0
    /** True the instant the countdown reaches zero (drives the rest-complete beep). */
    val isFinished: Boolean get() = remainingSeconds <= 0
    /** 0f..1f elapsed fraction for a ring/progress rendering. */
    val progress: Double
        get() = if (totalSeconds <= 0) 1.0 else 1.0 - (remainingSeconds.toDouble() / totalSeconds)
}

data class WorkoutSessionUiState(
    val loading: Boolean = true,
    /** The local draft this screen logs against (ADR-0012 Decision 1). */
    val draft: WorkoutSessionDraft? = null,
    val error: String? = null,
    val prompt: SessionPrompt? = null,
    /** Set once skip/discard succeeded (and after the finish recap is dismissed); the route pops back. */
    val closed: Boolean = false,
    /** Finish succeeded — show the post-workout recap over the retained draft snapshot before popping. */
    val completed: Boolean = false,
    val recap: String? = null,
    val recapLoading: Boolean = false,
    /**
     * What each exercise was performed last time, keyed by exerciseId (from the
     * most recent COMPLETED session, cross-program). Prefill falls back to the
     * designed target until this best-effort fetch lands (IMPL-COACH PR2).
     */
    val lastSets: Map<String, List<LoggedSet>> = emptyMap(),
    /**
     * Set for one frame when logging a set completes the *whole* session: the
     * logger auto-opens the finish summary (no trailing rest) and plays a chime.
     */
    val autoCompleted: Boolean = false,
    /**
     * #5 — whether the workout is underway. Seeded true when the logger opens onto
     * an already-existing draft (a resume), so the "Start workout" gate never
     * re-appears; flipped by [markStarted] on a brand-new session.
     */
    val started: Boolean = false,
)

class WorkoutSessionViewModel(
    private val repository: WorkoutSessionRepository,
    private val programId: String,
    private val scheduledId: String,
) : ViewModel() {

    /** Overridable in tests so set timestamps / rest derivation are deterministic. */
    var now: () -> Instant = { Clock.System.now() }

    private val _state = MutableStateFlow(WorkoutSessionUiState())
    val state: StateFlow<WorkoutSessionUiState> = _state.asStateFlow()

    /**
     * THE single rest-timer source. One [StateFlow], one ticker (see
     * [RestTimerHolder]). The SwiftUI overlay, the AVAudioPlayer beep, and the
     * Live Activity content-state all read THIS — never a second clock.
     */
    private val restHolder = RestTimerHolder(viewModelScope) { now() }
    val restTimer: StateFlow<RestTimerState?> get() = restHolder.state

    init {
        // Best-effort prior-performance fetch, independent of the draft load.
        viewModelScope.launch {
            val last = runCatching { repository.lastSets(programId, scheduledId) }.getOrDefault(emptyMap())
            _state.update { it.copy(lastSets = last) }
        }
        viewModelScope.launch {
            // #5: an existing draft means we're resuming (return-to-app / process
            // death) — never re-show the Start gate.
            if (repository.peekDraft(programId, scheduledId) != null) {
                _state.update { it.copy(started = true) }
            }
            val started = repository.start(programId, scheduledId)
            started.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't start the workout") }
            }
            if (started.isFailure) return@launch
            repository.observeDraft(programId, scheduledId).collect { draft ->
                _state.update { st ->
                    // After a terminal action the draft row disappears; keep the last
                    // snapshot so the closing frame doesn't flash empty.
                    if (draft == null) st.copy(loading = false)
                    else st.copy(loading = false, draft = draft, error = null)
                }
            }
        }
    }

    /**
     * Check off row [setIndex] of one prescription (append a defaulted [LoggedSet]
     * and start the prescribed rest), or un-check an already-logged row.
     */
    fun toggleSet(key: PrescriptionKey, setIndex: Int) {
        val draft = _state.value.draft ?: return
        val current = draft.logged[key].orEmpty()
        if (setIndex < current.size) {
            persistSets(key, current.toMutableList().apply { removeAt(setIndex) })
        } else {
            val updated = current + newSet(draft, key)
            persistSets(key, updated)
            startRestOrComplete(draft, key, updated, now())
        }
    }

    /**
     * Log the next set from an inline edit on the pending row: start from the same
     * defaults a check-off would apply, then overlay whichever field(s) were typed
     * (weight/reps/rpe/rir). Starts the prescribed rest, exactly like [toggleSet].
     */
    fun logSet(key: PrescriptionKey, edited: LoggedSet) {
        val draft = _state.value.draft ?: return
        val base = newSet(draft, key)
        val set = base.copy(
            weightLbs = edited.weightLbs ?: base.weightLbs,
            reps = edited.reps ?: base.reps,
            rpe = edited.rpe ?: base.rpe,
            // The final set's RIR pick — dropping these silently discarded every
            // tapped chip on Android (observations all landed ABSENT).
            rir = edited.rir ?: base.rir,
            rirSource = edited.rirSource ?: base.rirSource,
        )
        val updated = draft.logged[key].orEmpty() + set
        persistSets(key, updated)
        startRestOrComplete(draft, key, updated, now())
    }

    /**
     * Log a timed exercise's set with the measured [durationSeconds]. A completed
     * timed exercise starts the prescribed rest before the next one (IMPL-FIXPACK-01
     * Phase 3); between sets of the SAME hold there's no rest.
     */
    fun logTimedSet(key: PrescriptionKey, durationSeconds: Int, timedEffort: String? = null) {
        val draft = _state.value.draft ?: return
        val current = draft.logged[key].orEmpty()
        val at = now()
        val set = LoggedSet(
            durationSeconds = durationSeconds,
            restSeconds = restSecondsBefore(draft, at),
            completedAt = at,
            timedEffort = timedEffort,
        )
        val updated = current + set
        persistSets(key, updated)
        val exerciseDone = updated.size >= (draft.prescription(key)?.sets ?: 1)
        startRestOrComplete(draft, key, updated, at, startRest = exerciseDone)
    }

    /** Replace one logged set after an inline weight/reps/duration edit. */
    fun editSet(key: PrescriptionKey, setIndex: Int, set: LoggedSet) {
        val draft = _state.value.draft ?: return
        val current = draft.logged[key].orEmpty()
        if (setIndex !in current.indices) return
        persistSets(key, current.toMutableList().also { it[setIndex] = set })
    }

    /**
     * After a set is logged, either start the prescribed rest — or, if that set
     * completed the whole session, skip the rest and auto-open the finish summary
     * (the "auto complete workout" behaviour + completion chime). [updated] is the
     * list about to land on the draft, tested before the round-trip so the check
     * doesn't lag a frame. [startRest] is false for timed holds between sub-sets.
     */
    private fun startRestOrComplete(
        draft: WorkoutSessionDraft,
        key: PrescriptionKey,
        updated: List<LoggedSet>,
        at: Instant,
        startRest: Boolean = true,
    ) {
        if (draft.isComplete(draft.logged + (key to updated))) {
            restHolder.clear()
            _state.update { it.copy(prompt = SessionPrompt.FINISH_SUMMARY, autoCompleted = true) }
        } else if (startRest) {
            draft.prescription(key)?.restSeconds?.let { restHolder.startRest(it) }
        }
    }

    /** The route has played the completion chime; clear the one-shot flag. */
    fun consumeAutoCompleted() = _state.update { it.copy(autoCompleted = false) }

    // ---- rest / get-ready timer controls (all target the single source) ----

    /** "Skip rest" — stop the shared countdown early. */
    fun dismissRest() = restHolder.clear()

    /** Start the get-ready pre-roll before a timed hold (same source as rest). */
    fun startGetReady(seconds: Int) = restHolder.startGetReady(seconds)

    /**
     * Start a live isometric hold of [seconds] (#275), routed through the single
     * self-ticking [RestTimerHolder] so its halfway / ten-second / finish cues fire
     * from one wall-clock source — surviving app backgrounding mid-hold and never
     * double-firing. The SwiftUI card renders the count-up + drives logging.
     */
    fun startHold(seconds: Int) = restHolder.startHold(seconds)

    /** Freeze the countdown (the get-ready Pause control). */
    fun pauseTimer() = restHolder.pause()

    /** Resume a frozen countdown. */
    fun resumeTimer() = restHolder.resume()

    /** Restart the countdown from the top. */
    fun resetTimer() = restHolder.reset()

    /** #5 — latch the session as underway and re-anchor the draft clock to now. */
    fun markStarted() {
        _state.update { it.copy(started = true) }
        viewModelScope.launch { repository.markStarted(programId, scheduledId) }
    }

    fun requestFinish() = _state.update { it.copy(prompt = SessionPrompt.FINISH_SUMMARY) }
    fun requestSkip() = _state.update { it.copy(prompt = SessionPrompt.SKIP) }
    fun requestDiscard() = _state.update { it.copy(prompt = SessionPrompt.DISCARD) }
    fun dismissPrompt() = _state.update { it.copy(prompt = null) }

    /**
     * Upload COMPLETED with all logged actuals (ADR-0012 D2/D5), then surface the
     * post-workout recap. The AI recap is fetched best-effort afterward — it never
     * blocks finishing.
     */
    fun confirmFinish(feeling: Int? = null) {
        viewModelScope.launch {
            repository.finish(programId, scheduledId, feeling)
                .onSuccess {
                    restHolder.clear()
                    _state.update { it.copy(prompt = null, completed = true, recapLoading = true) }
                    val recap = runCatching { repository.fetchRecap(programId, scheduledId) }.getOrNull()
                    _state.update { it.copy(recap = recap, recapLoading = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(prompt = null, error = e.message ?: "Couldn't finish the workout") }
                }
        }
    }

    /** Dismiss the post-finish recap and pop the logger. */
    fun dismissCompleted() = _state.update { it.copy(closed = true) }

    /** Upload SKIPPED (clears actuals, IMPL-17 D4) and close. */
    fun confirmSkip() = close("Couldn't skip the session") { repository.skip(programId, scheduledId) }

    /** Throw the draft away locally — nothing reaches the backend. */
    fun confirmDiscard() = close("Couldn't discard the draft") { repository.discard(programId, scheduledId) }

    private fun close(failureMessage: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action()
                .onSuccess {
                    restHolder.clear()
                    _state.update { it.copy(prompt = null, closed = true) }
                }
                .onFailure { e ->
                    _state.update { it.copy(prompt = null, error = e.message ?: failureMessage) }
                }
        }
    }

    private fun persistSets(key: PrescriptionKey, sets: List<LoggedSet>) {
        viewModelScope.launch {
            repository.updateSets(programId, scheduledId, key, sets).onFailure { e ->
                _state.update { it.copy(error = e.message ?: "Couldn't save the set") }
            }
        }
    }

    /**
     * Defaults for a freshly checked-off set. Weight/reps carry from the previous
     * set of the same prescription, then fall back to the history-grounded design
     * target ([prefillFor]) so the row lands pre-filled rather than blank. A timed
     * exercise fills [LoggedSet.durationSeconds] instead. [restSeconds] is the
     * actual rest taken — the full-actuals capture of ADR-0012 Decision 2.
     */
    private fun newSet(draft: WorkoutSessionDraft, key: PrescriptionKey): LoggedSet {
        val prescription = draft.prescription(key)
        val logged = draft.logged[key].orEmpty()
        val at = now()
        val prefill = prescription?.let { prefillFor(it, logged, _state.value.lastSets) }
        return LoggedSet(
            weightLbs = prefill?.weightLbs,
            reps = prefill?.reps,
            rpe = null,
            restSeconds = restSecondsBefore(draft, at),
            completedAt = at,
            durationSeconds = prefill?.durationSeconds,
        )
    }

    private fun restSecondsBefore(draft: WorkoutSessionDraft, at: Instant): Int? {
        val lastAt = draft.logged.values.flatten()
            .mapNotNull { it.completedAt }
            .maxOrNull()
            ?: return null
        val seconds = (at.epochSeconds - lastAt.epochSeconds)
        return if (seconds in 1..MAX_TRACKED_REST_SECONDS) seconds.toInt() else null
    }

    private fun WorkoutSessionDraft.prescription(key: PrescriptionKey): Prescription? =
        scheduled.session?.blocks
            ?.firstOrNull { it.blockId == key.blockId }
            ?.prescriptions
            ?.firstOrNull { it.orderIndex == key.orderIndex }

    override fun onCleared() {
        restHolder.dispose()
        super.onCleared()
    }

    companion object {
        /** A gap longer than this is a break, not a rest between sets. */
        const val MAX_TRACKED_REST_SECONDS: Long = 30L * 60
    }
}
