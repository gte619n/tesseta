package com.gte619n.healthfitness.feature.workouts.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
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

    /**
     * IMPL-COACH PR2 — what each exercise was performed last time, keyed by
     * exerciseId, used to prefill new sets with the literal previous session.
     * Fetched best-effort on open; empty until it lands (prefill falls back to
     * the designed target meanwhile).
     */
    private var lastSetsByExercise: Map<String, List<LoggedSet>> = emptyMap()

    init {
        // Best-effort prior-performance fetch, independent of the draft load below.
        viewModelScope.launch {
            lastSetsByExercise = repository.lastSets(programId, scheduledId)
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
            persistSets(key, current + newSet(draft, key))
            draft.prescription(key)?.restSeconds?.let { timers.startRest(it, now()) }
        }
    }

    /**
     * Log a timed exercise's set with the measured [durationSeconds] (from the
     * hold timer) rather than the prescribed default, and start the prescribed
     * rest countdown — the timed counterpart to checking off a rep set.
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
        persistSets(key, current + set)
        draft.prescription(key)?.restSeconds?.let { timers.startRest(it, at) }
    }

    /** Replace one logged set after an inline weight/reps/duration edit. */
    fun editSet(key: PrescriptionKey, setIndex: Int, set: LoggedSet) {
        val draft = _state.value.draft ?: return
        val current = draft.logged[key].orEmpty()
        if (setIndex !in current.indices) return
        persistSets(key, current.toMutableList().also { it[setIndex] = set })
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
                    timers.clearRest()
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
                    timers.clearRest()
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
        val previous = logged.lastOrNull()
        // The matching set from the last time this exercise was performed.
        val lastTime = prescription?.exerciseId
            ?.let { lastSetsByExercise[it] }
            ?.getOrNull(logged.size)
        val at = now()
        val timed = prescription?.isTimed == true
        return LoggedSet(
            // Carry within the session first, then the literal previous session,
            // then the designed target (IMPL-COACH PR2).
            weightLbs = if (timed) null else {
                previous?.weightLbs ?: lastTime?.weightLbs ?: prescription?.targetWeightLbs
            },
            reps = if (timed) null else {
                previous?.reps ?: lastTime?.reps ?: prescription?.repsMax ?: prescription?.repsMin
            },
            rpe = null,
            restSeconds = restSecondsBefore(draft, at),
            completedAt = at,
            durationSeconds = if (timed) {
                previous?.durationSeconds ?: lastTime?.durationSeconds ?: prescription?.durationSeconds
            } else {
                null
            },
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
    }
}
