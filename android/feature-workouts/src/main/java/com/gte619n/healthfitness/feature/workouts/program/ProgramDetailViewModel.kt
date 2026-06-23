package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.ProgramActivationInvalidException
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val error: String? = null,
)

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

    fun refresh() = load()

    /**
     * Activate (or re-activate) this program: the backend materializes its
     * sessions and marks it ACTIVE, then we reload so the status + "this week"
     * strip populate (which is what makes a workout runnable).
     */
    fun activate() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, activationIssues = emptyList()) }
            repository.activate(programId)
                .onSuccess { load() }
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

    private fun load() {
        _state.update { it.copy(loading = true, today = today, error = null) }
        viewModelScope.launch {
            // The "this week" range is derived from the device date; the
            // weekIndexInPhase / isDeload fields come from the backend
            // (authoritative — the client never recomputes deload status).
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekEnd = weekStart.plusDays(6)

            val deepDeferred = async { repository.get(programId) }
            val calendarDeferred = async { repository.calendar(programId, weekStart, weekEnd) }
            // The "log a past session" pool (G3): everything from the start of
            // time up to the day before this week. Backend only logs against an
            // already-materialized session, so this is what the picker offers.
            val pastDeferred = async {
                repository.calendar(programId, LocalDate.of(1970, 1, 1), weekStart.minusDays(1))
            }

            val deepResult = deepDeferred.await()
            val calendarResult = calendarDeferred.await()
            val pastResult = pastDeferred.await()

            deepResult
                .onSuccess { program ->
                    val week = calendarResult.getOrDefault(emptyList())
                        .sortedBy { it.date }
                    val past = pastResult.getOrDefault(emptyList())
                        .filter { it.date <= today }
                        .sortedByDescending { it.date }
                    _state.update {
                        it.copy(
                            loading = false,
                            program = program,
                            thisWeek = week,
                            pastSessions = past,
                            error = null,
                        )
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
