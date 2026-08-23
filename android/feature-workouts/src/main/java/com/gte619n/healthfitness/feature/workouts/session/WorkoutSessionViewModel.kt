package com.gte619n.healthfitness.feature.workouts.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.data.profile.ProfileRepository
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** Which confirmation the logger is showing (finish summary, skip, discard). */
enum class SessionPrompt { FINISH_SUMMARY, SKIP, DISCARD }

data class WorkoutSessionUiState(
    val loading: Boolean = true,
    /** The local draft this screen logs against (ADR-0012 Decision 1). */
    val draft: WorkoutSessionDraft? = null,
    val error: String? = null,
    val prompt: SessionPrompt? = null,
    /** Set once skip/discard succeeded (and after the finish recap is dismissed); the route pops back. */
    val closed: Boolean = false,
    /**
     * IMPL-COACH — finish succeeded; show the post-workout recap summary (over
     * the retained draft snapshot) before popping. [recap] is the best-effort AI
     * coach note (null when unavailable); [recapLoading] covers the fetch.
     */
    val completed: Boolean = false,
    val recap: String? = null,
    val recapLoading: Boolean = false,
    /**
     * IMPL-COACH PR2 — what each exercise was performed last time, keyed by
     * exerciseId (from the most recent COMPLETED session, cross-program). The
     * logger's pending row and the coach cue prefill from this. Empty until the
     * best-effort fetch lands; prefill falls back to the designed target.
     */
    val lastSets: Map<String, List<LoggedSet>> = emptyMap(),
    /**
     * Set for one frame when logging a set completes the *whole* session: the
     * logger auto-opens the finish summary (no trailing rest) and plays a
     * completion chime. The route consumes it via [consumeAutoCompleted].
     */
    val autoCompleted: Boolean = false,
    /**
     * #4 exercise substitution — the movements executable at this session's gym,
     * loaded on demand when the swap picker opens. [substituteLoading] covers the
     * fetch; [substituteError] is a best-effort load failure (offline).
     */
    val substituteOptions: List<ExerciseSummary> = emptyList(),
    val substituteLoading: Boolean = false,
    val substituteError: String? = null,
    /**
     * #9 — true only for the app owner (by account email). Gates the demo-image
     * "flag as bad" affordance so ordinary users never see it.
     */
    val isOwner: Boolean = false,
)

/**
 * ADR-0012 (IMPL-AND-17) — the phone logger over one device-local session
 * draft. start() resumes an existing draft or snapshots the scheduled session;
 * every set edit goes straight to Room (so the draft survives process death);
 * finish/skip route the completion upsert through the offline outbox via the
 * repository. The rest countdown lives on the shared [WorkoutSessionTimers]
 * bus so the foreground notification (and later Wear) render the same timer.
 */
@HiltViewModel
class WorkoutSessionViewModel @Inject constructor(
    private val repository: WorkoutSessionRepository,
    private val timers: WorkoutSessionTimers,
    private val profileRepository: ProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Overridable in tests so set timestamps / rest derivation are deterministic.
    var now: () -> Instant = Instant::now

    private val programId: String =
        checkNotNull(savedStateHandle[WorkoutsRoutes.ARG_PROGRAM_ID]) {
            "WorkoutSessionViewModel requires a '${WorkoutsRoutes.ARG_PROGRAM_ID}' nav argument"
        }
    private val scheduledId: String =
        checkNotNull(savedStateHandle[WorkoutsRoutes.ARG_SCHEDULED_ID]) {
            "WorkoutSessionViewModel requires a '${WorkoutsRoutes.ARG_SCHEDULED_ID}' nav argument"
        }

    private val _state = MutableStateFlow(WorkoutSessionUiState())
    val state: StateFlow<WorkoutSessionUiState> = _state.asStateFlow()

    /** The shared rest countdown (also rendered by the foreground notification). */
    val restTimer: StateFlow<WorkoutSessionTimers.RestTimer?> = timers.rest

    init {
        // Best-effort prior-performance fetch, independent of the draft load
        // below; surfaced into state so the pending row and coach cue can
        // prefill from the literal previous session (IMPL-COACH PR2).
        viewModelScope.launch {
            val last = repository.lastSets(programId, scheduledId)
            _state.update { it.copy(lastSets = last) }
        }
        // Best-effort: register an ad-hoc / past-schedule session on the server
        // as soon as it's opened (idempotent), so it's first-class mid-session
        // rather than only after completion. Never blocks the offline-first
        // logger; failure is swallowed.
        viewModelScope.launch {
            runCatching { repository.ensureServerSession(programId, scheduledId) }
        }
        // #9 — owner gating for the demo-image flag affordance. Best-effort:
        // read the cached profile first (instant), then refresh; any failure
        // just leaves the flag hidden.
        viewModelScope.launch {
            val cachedEmail = runCatching { profileRepository.cached()?.email }.getOrNull()
            val email = cachedEmail
                ?: runCatching { profileRepository.get().getOrNull()?.email }.getOrNull()
            if (email != null && OWNER_EMAILS.any { it.equals(email, ignoreCase = true) }) {
                _state.update { it.copy(isOwner = true) }
            }
        }
        viewModelScope.launch {
            val started = repository.start(programId, scheduledId)
            started.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Couldn't start the workout")
                }
            }
            if (started.isFailure) return@launch
            repository.observeDraft(programId, scheduledId).collect { draft ->
                _state.update { st ->
                    // After a terminal action the draft row disappears; keep the
                    // last snapshot so the closing frame doesn't flash empty.
                    if (draft == null) {
                        st.copy(loading = false)
                    } else {
                        st.copy(loading = false, draft = draft, error = null)
                    }
                }
            }
        }
    }

    /**
     * Check off row [setIndex] of one prescription (appending a defaulted
     * [LoggedSet] and starting the prescribed rest countdown), or un-check an
     * already-logged row (removing that set).
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
     * Log the next set of one prescription from an inline edit on the pending
     * row (before the user taps the circle): start from the same defaults a
     * check-off would apply, then overlay whichever field(s) the user typed.
     * Starts the prescribed rest countdown, exactly like [toggleSet].
     */
    fun logSet(key: PrescriptionKey, edited: LoggedSet) {
        val draft = _state.value.draft ?: return
        val base = newSet(draft, key)
        val set = base.copy(
            weightLbs = edited.weightLbs ?: base.weightLbs,
            reps = edited.reps ?: base.reps,
            rpe = edited.rpe ?: base.rpe,
        )
        val updated = draft.logged[key].orEmpty() + set
        persistSets(key, updated)
        startRestOrComplete(draft, key, updated, now())
    }

    /**
     * Log a timed exercise's set with the measured [durationSeconds] (from the
     * hold timer) rather than the prescribed default — the timed counterpart to
     * checking off a rep set. Unlike a rep set it starts *no* rest countdown: the
     * guided stretch flow paces the next hold with its own "get ready" pre-roll
     * (or jumps straight to the next lift), so a rest overlay would only fight it.
     */
    fun logTimedSet(key: PrescriptionKey, durationSeconds: Int) {
        val draft = _state.value.draft ?: return
        val current = draft.logged[key].orEmpty()
        val at = now()
        val set = LoggedSet(
            durationSeconds = durationSeconds,
            restSeconds = restSecondsBefore(draft, at),
            completedAt = at,
        )
        val updated = current + set
        persistSets(key, updated)
        startRestOrComplete(draft, key, updated, at, startRest = false)
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
     * completed the whole session, skip the rest entirely and auto-open the
     * finish summary (the "auto complete workout" behaviour: no dangling rest
     * after the last set, straight to the summary + completion chime).
     * [updated] is the sets list about to land on the draft, tested before the
     * Room round-trip so the check doesn't lag a frame behind. [startRest] is
     * false for timed holds, which pace themselves through the guided flow rather
     * than a rest countdown; completion detection still runs either way.
     */
    private fun startRestOrComplete(
        draft: WorkoutSessionDraft,
        key: PrescriptionKey,
        updated: List<LoggedSet>,
        at: Instant,
        startRest: Boolean = true,
    ) {
        if (draft.isComplete(draft.logged + (key to updated))) {
            timers.clearRest()
            _state.update { it.copy(prompt = SessionPrompt.FINISH_SUMMARY, autoCompleted = true) }
        } else if (startRest) {
            draft.prescription(key)?.restSeconds?.let { timers.startRest(it, at) }
        }
    }

    /** The route has played the completion chime; clear the one-shot flag. */
    fun consumeAutoCompleted() = _state.update { it.copy(autoCompleted = false) }

    /**
     * #4 — load the movements executable at this session's gym for the swap
     * picker. Reloads on each open so a just-updated gym is reflected; the
     * options stay until the next load so re-opening is instant.
     */
    fun loadSubstituteOptions() {
        val locationId = _state.value.draft?.scheduled?.locationId
        if (locationId.isNullOrBlank()) {
            _state.update { it.copy(substituteError = "This session has no gym to swap within.") }
            return
        }
        _state.update { it.copy(substituteLoading = true, substituteError = null) }
        viewModelScope.launch {
            repository.availableExercises(locationId)
                .onSuccess { options ->
                    _state.update { it.copy(substituteLoading = false, substituteOptions = options) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            substituteLoading = false,
                            substituteError = e.message ?: "Couldn't load alternatives",
                        )
                    }
                }
        }
    }

    /** #4 — swap the exercise at [key] for [exercise] in the live draft. */
    fun substituteExercise(key: PrescriptionKey, exercise: ExerciseSummary) {
        _state.value.draft ?: return
        // A swap starts the slot fresh — drop any rest tied to the old movement.
        timers.clearRest()
        viewModelScope.launch {
            repository.substituteExercise(programId, scheduledId, key, exercise).onFailure { e ->
                _state.update { it.copy(error = e.message ?: "Couldn't swap the exercise") }
            }
        }
    }

    /** #9 — owner flags a demo frame as bad; no-op for non-owners (defense in depth). */
    fun flagFrame(exerciseId: String, frameKey: String) {
        if (!_state.value.isOwner) return
        viewModelScope.launch {
            repository.flagFrame(exerciseId, frameKey).onFailure { e ->
                _state.update { it.copy(error = e.message ?: "Couldn't flag the image") }
            }
        }
    }

    /** "Skip rest" — stop the shared countdown early. */
    fun dismissRest() = timers.clearRest()

    fun requestFinish() = _state.update { it.copy(prompt = SessionPrompt.FINISH_SUMMARY) }

    fun requestSkip() = _state.update { it.copy(prompt = SessionPrompt.SKIP) }

    fun requestDiscard() = _state.update { it.copy(prompt = SessionPrompt.DISCARD) }

    fun dismissPrompt() = _state.update { it.copy(prompt = null) }

    /**
     * Upload COMPLETED with all logged actuals (ADR-0012 D2/D5), then surface
     * the post-workout recap summary. The AI recap is fetched best-effort
     * afterward (IMPL-COACH) — it never blocks finishing, and the summary shows
     * with or without it. [dismissCompleted] pops the route.
     */
    fun confirmFinish() {
        viewModelScope.launch {
            repository.finish(programId, scheduledId)
                .onSuccess {
                    timers.clearSession()
                    _state.update { it.copy(prompt = null, completed = true, recapLoading = true) }
                    val recap = repository.fetchRecap(programId, scheduledId)
                    _state.update { it.copy(recap = recap, recapLoading = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(prompt = null, error = e.message ?: "Couldn't finish the workout")
                    }
                }
        }
    }

    /** Dismiss the post-finish recap summary and pop the logger. */
    fun dismissCompleted() = _state.update { it.copy(closed = true) }

    /** Upload SKIPPED (clears actuals, IMPL-17 D4) and close. */
    fun confirmSkip() = close("Couldn't skip the session") {
        repository.skip(programId, scheduledId)
    }

    /** Throw the draft away locally — nothing reaches the backend. */
    fun confirmDiscard() = close("Couldn't discard the draft") {
        repository.discard(programId, scheduledId)
    }

    private fun close(failureMessage: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action()
                .onSuccess {
                    timers.clearSession()
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
     * Defaults for a freshly checked-off set. Weight/reps carry from the
     * previous set of the same prescription, then fall back to the
     * history-grounded design target ([Prescription.targetWeightLbs] / the
     * prescribed rep range) so the row lands pre-filled with what to lift rather
     * than blank. RPE is left for program design (no mid-workout capture). A
     * timed exercise (stretch/mobility) fills [LoggedSet.durationSeconds] from
     * the prescribed hold instead of weight/reps. [restSeconds] is the actual
     * rest taken — the full-actuals capture of ADR-0012 Decision 2.
     */
    private fun newSet(draft: WorkoutSessionDraft, key: PrescriptionKey): LoggedSet {
        val prescription = draft.prescription(key)
        val logged = draft.logged[key].orEmpty()
        val at = now()
        // Carry within the session first, then the literal previous session,
        // then the designed target (IMPL-COACH PR2) — the same source of truth
        // the UI shows on the pending row.
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
        val seconds = Duration.between(lastAt, at).seconds
        return if (seconds in 1..MAX_TRACKED_REST_SECONDS) seconds.toInt() else null
    }

    private fun WorkoutSessionDraft.prescription(key: PrescriptionKey): Prescription? =
        scheduled.session?.blocks
            ?.firstOrNull { it.blockId == key.blockId }
            ?.prescriptions
            ?.firstOrNull { it.orderIndex == key.orderIndex }

    companion object {
        /** A gap longer than this is a break, not a rest between sets. */
        const val MAX_TRACKED_REST_SECONDS: Long = 30L * 60

        /** #9 — the app owner accounts; the only ones shown the demo-image flag control. */
        val OWNER_EMAILS: Set<String> = setOf("evan.ruff@gmail.com", "evan.ruff@oxos.com")
    }
}
