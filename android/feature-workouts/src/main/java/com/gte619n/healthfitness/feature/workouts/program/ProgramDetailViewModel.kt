package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.domain.workouts.program.ProgramActivationInvalidException
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class ProgramDetailUiState(
    val loading: Boolean = true,
    val program: WorkoutProgram? = null,
    /** Current-week scheduled sessions; date range derived from the device. */
    val thisWeek: List<ScheduledWorkout> = emptyList(),
    /**
     * Earlier materialized sessions dated on/before today but outside the
     * current week — the "log a past session" pool (IMPL-STAB G3). Newest first.
     * Backend only logs against an existing scheduled session, so this is the
     * set the user can pick from; empty when nothing is materialized in the past.
     */
    val pastSessions: List<ScheduledWorkout> = emptyList(),
    /** Validation issues from a failed activation (422); shown inline (G1). */
    val activationIssues: List<String> = emptyList(),
    /** Whether the past-session picker sheet is open (G3). */
    val showPastSessions: Boolean = false,
    /** Whether the edit-details (title/description) sheet is open (G4). */
    val editing: Boolean = false,
    /** In-flight save of the edit sheet (G4). */
    val savingEdit: Boolean = false,
    /** In-progress local session draft for THIS program (resume affordance). */
    val activeDraft: WorkoutSessionDraft? = null,
    /** Newest parked completion upload for THIS program (IMPL-17 A10/Q3). */
    val parkedCompletion: ParkedCompletion? = null,
    /** Restore/discard failure for the parked banner (kept off [error]). */
    val parkedError: String? = null,
    /** Set once a restore succeeded; the route opens the logger and consumes it. */
    val restoredSession: ParkedCompletion? = null,
    /** Device date, fixed by the VM so the "start today" affordance is testable. */
    val today: LocalDate = LocalDate.now(),
    /** The program's nutrition guidance; null = no "Apply nutrition" action. */
    val nutritionGuidance: NutritionGuidance? = null,
    val applyingNutrition: Boolean = false,
    /** One-shot: the macros just applied to the target, for a confirmation message. */
    val appliedNutrition: Macros? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgramDetailViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
    private val sessionRepository: WorkoutSessionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Overridable in tests so the "this week" range is deterministic.
    var today: LocalDate = LocalDate.now()

    private val programId: String =
        checkNotNull(savedStateHandle[WorkoutsRoutes.ARG_PROGRAM_ID]) {
            "ProgramDetailViewModel requires a '${WorkoutsRoutes.ARG_PROGRAM_ID}' nav argument"
        }

    /** Bumped by [refresh] to force a re-subscription (and a fresh network fill). */
    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(ProgramDetailUiState())
    val state: StateFlow<ProgramDetailUiState> = _state.asStateFlow()

    init {
        load()
        // ADR-0012: surface this program's in-flight local draft so the detail
        // shows a Resume banner (and hides Start while one is active).
        viewModelScope.launch {
            sessionRepository.observeDrafts().collect { drafts ->
                _state.update { st ->
                    st.copy(activeDraft = drafts.firstOrNull { it.programId == programId })
                }
            }
        }
        // IMPL-17 Q3: surface this program's parked completion uploads so the
        // detail offers the "couldn't sync — restore to review" recovery.
        viewModelScope.launch {
            sessionRepository.observeParkedCompletions().collect { parked ->
                _state.update { st ->
                    st.copy(parkedCompletion = parked.firstOrNull { it.programId == programId })
                }
            }
        }
    }

    fun refresh() = refreshToken.update { it + 1 }

    /**
     * Activate (or re-activate) this program: the backend materializes its
     * sessions and marks it ACTIVE. The reactive [load] stream then re-emits the
     * new status + "this week" strip from the mirror (activate refreshes it), so
     * no explicit reload is needed — we just clear the inline issue list.
     */
    fun activate() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, activationIssues = emptyList()) }
            repository.activate(programId)
                .onSuccess {
                    _state.update { it.copy(loading = false, activationIssues = emptyList(), error = null) }
                }
                .onFailure { e ->
                    // IMPL-STAB G1: a 422 carries the actionable validator issue
                    // list — surface it inline instead of a generic message.
                    if (e is ProgramActivationInvalidException) {
                        _state.update {
                            it.copy(loading = false, error = null, activationIssues = e.issues)
                        }
                    } else {
                        _state.update {
                            it.copy(
                                loading = false,
                                error = e.message ?: "Couldn't activate the program",
                                activationIssues = emptyList(),
                            )
                        }
                    }
                }
        }
    }

    fun dismissActivationIssues() = _state.update { it.copy(activationIssues = emptyList()) }

    // --- IMPL-STAB G4: edit title/description ---

    fun startEdit() = _state.update { it.copy(editing = true, error = null) }

    fun cancelEdit() = _state.update { it.copy(editing = false) }

    /** PATCH the program's metadata; closes the sheet and reloads on success. */
    fun saveEdit(title: String, description: String?) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            _state.update { it.copy(error = "Title can't be empty") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(savingEdit = true, error = null) }
            repository.updateDetails(programId, trimmedTitle, description?.trim()?.ifBlank { null })
                .onSuccess { updated ->
                    _state.update { it.copy(savingEdit = false, editing = false, program = updated) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(savingEdit = false, error = e.message ?: "Couldn't save changes")
                    }
                }
        }
    }

    // --- IMPL-STAB G3: log a past session ---

    fun openPastSessions() = _state.update { it.copy(showPastSessions = true) }

    fun dismissPastSessions() = _state.update { it.copy(showPastSessions = false) }

    /**
     * Delete a logged session from history: revert it to PLANNED (clears the
     * actuals and removes the fanned-out workout server-side) and reload so the
     * picker reflects the un-logged status. The day stays on the calendar to redo.
     */
    fun deleteSession(scheduledId: String) {
        viewModelScope.launch {
            sessionRepository.reset(programId, scheduledId)
                .onSuccess { load() }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message ?: "Couldn't delete the workout") }
                }
        }
    }

    /** Re-materialize the parked completion as a draft and open the logger. */
    fun restoreParked(parked: ParkedCompletion) {
        viewModelScope.launch {
            sessionRepository.restoreParked(parked.programId, parked.scheduledId)
                .onSuccess {
                    _state.update { it.copy(parkedError = null, restoredSession = parked) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(parkedError = e.message ?: "Couldn't restore the workout")
                    }
                }
        }
    }

    /** Give up on a parked completion (offered when the session is gone). */
    fun discardParked(parked: ParkedCompletion) {
        viewModelScope.launch {
            sessionRepository.discardParked(parked.programId, parked.scheduledId)
                .onSuccess { _state.update { it.copy(parkedError = null) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(parkedError = e.message ?: "Couldn't discard the workout")
                    }
                }
        }
    }

    fun consumeRestoredSession() = _state.update { it.copy(restoredSession = null) }

    // --- Apply the program's nutrition guidance as the macro target ---

    /** Apply this program's nutrition guidance as the user's daily macro target. */
    fun applyNutrition() {
        if (_state.value.applyingNutrition) return
        _state.update { it.copy(applyingNutrition = true, error = null) }
        viewModelScope.launch {
            repository.applyNutritionTarget(programId)
                .onSuccess { applied ->
                    _state.update { it.copy(applyingNutrition = false, appliedNutrition = applied) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            applyingNutrition = false,
                            error = e.message ?: "Couldn't update nutrition target",
                        )
                    }
                }
        }
    }

    fun consumeAppliedNutrition() = _state.update { it.copy(appliedNutrition = null) }

    private fun load() {
        viewModelScope.launch {
            refreshToken
                .flatMapLatest {
                    _state.update { it.copy(loading = true, today = today, error = null) }
                    // The "this week" range is derived from the device date; the
                    // weekIndexInPhase / isDeload fields come from the backend
                    // (authoritative — the client never recomputes deload status).
                    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val weekEnd = weekStart.plusDays(6)
                    // Best-effort, online-only nutrition guidance (drives the
                    // "Apply nutrition" action). Emits null first so the program +
                    // schedule render immediately, then the fetched value when it
                    // lands — never blocking the screen on this network call.
                    val guidanceFlow = flow<NutritionGuidance?> {
                        emit(null)
                        emit(runCatching { repository.nutritionGuidance(programId).getOrNull() }.getOrNull())
                    }
                    // The "log a past session" pool (G3): everything up to the day
                    // before this week; the backend only logs against an existing
                    // materialized session, so this is what the picker offers.
                    combine(
                        repository.observeProgram(programId),
                        // A schedule read failing must NOT blank the whole screen —
                        // the program still renders, the strip just stays empty.
                        repository.observeCalendar(programId, weekStart, weekEnd)
                            .catch { emit(emptyList()) },
                        repository.observeCalendar(programId, LocalDate.of(1970, 1, 1), weekStart.minusDays(1))
                            .catch { emit(emptyList()) },
                        guidanceFlow,
                    ) { program, week, past, guidance ->
                        ProgramDetailLoad(program, week, past, guidance)
                    }
                        .map { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                }
                .collect { result ->
                    result
                        .onSuccess { data ->
                            if (data.program == null) {
                                _state.update {
                                    it.copy(loading = false, error = it.error ?: "Failed to load program")
                                }
                            } else {
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        program = data.program,
                                        thisWeek = data.thisWeek.sortedBy { s -> s.date },
                                        pastSessions = data.pastSessions
                                            .filter { s -> s.date <= today }
                                            .sortedByDescending { s -> s.date },
                                        nutritionGuidance = data.guidance,
                                        error = null,
                                    )
                                }
                            }
                        }
                        .onFailure { e ->
                            _state.update {
                                it.copy(loading = false, error = e.message ?: "Failed to load program")
                            }
                        }
                }
        }
    }
}

/** The reactive [ProgramDetailViewModel.load] payload — program tree, the week + past schedule strips, and best-effort nutrition guidance. */
private data class ProgramDetailLoad(
    val program: WorkoutProgram?,
    val thisWeek: List<ScheduledWorkout>,
    val pastSessions: List<ScheduledWorkout>,
    val guidance: NutritionGuidance?,
)
