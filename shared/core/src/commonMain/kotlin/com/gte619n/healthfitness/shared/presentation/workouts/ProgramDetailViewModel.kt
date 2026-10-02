package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.WorkoutProgramRepository
import com.gte619n.healthfitness.shared.data.WorkoutSessionRepository
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramActivationInvalidException
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.shared.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
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
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * IMPL-IOS-01 Phase 3 Wave D — KMP port of the Android `ProgramDetailViewModel`.
 * Keyed by [programId] (a nav arg on Android via SavedStateHandle, a constructor
 * param on KMP). Observes the deep program + this-week / past schedule strips,
 * folds in the local draft + parked-completion recovery banners, and offers
 * activate / edit-details / apply-nutrition / delete-session / restore-parked.
 */
data class ProgramDetailUiState(
    val loading: Boolean = true,
    val program: WorkoutProgram? = null,
    /** Current-week scheduled sessions; date range derived from the device. */
    val thisWeek: List<ScheduledWorkout> = emptyList(),
    /** Earlier materialized sessions on/before today, outside this week — the "log a past session" pool. Newest first. */
    val pastSessions: List<ScheduledWorkout> = emptyList(),
    /** Validation issues from a failed activation (422); shown inline. */
    val activationIssues: List<String> = emptyList(),
    val showPastSessions: Boolean = false,
    /** Whether the "continue program" length picker (1 week / full cycle) is open (#282). */
    val showContinuePicker: Boolean = false,
    /** In-flight continue call (extend the finished program). */
    val continuing: Boolean = false,
    val editing: Boolean = false,
    val savingEdit: Boolean = false,
    val activeDraft: WorkoutSessionDraft? = null,
    val parkedCompletion: ParkedCompletion? = null,
    val parkedError: String? = null,
    val restoredSession: ParkedCompletion? = null,
    val today: LocalDate,
    val nutritionGuidance: NutritionGuidance? = null,
    val applyingNutrition: Boolean = false,
    val appliedNutrition: Macros? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramDetailViewModel(
    private val repository: WorkoutProgramRepository,
    private val sessionRepository: WorkoutSessionRepository,
    private val programId: String,
    /** Overridable in tests so the "this week" range is deterministic. */
    var today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
) : ViewModel() {

    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(ProgramDetailUiState(today = today))
    val state: StateFlow<ProgramDetailUiState> = _state.asStateFlow()

    init {
        load()
        // Surface this program's in-flight local draft so the detail shows a
        // Resume banner (and hides Start while one is active).
        viewModelScope.launch {
            sessionRepository.observeDrafts().collect { drafts ->
                _state.update { st ->
                    st.copy(activeDraft = drafts.firstOrNull { it.programId == programId })
                }
            }
        }
        // Surface this program's parked completion uploads for the recovery flow.
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
     * sessions and marks it ACTIVE. The reactive [load] then re-emits, so we just
     * clear the inline issue list. A 422 surfaces its issue list inline.
     */
    fun activate() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, activationIssues = emptyList()) }
            repository.activate(programId)
                .onSuccess {
                    _state.update { it.copy(loading = false, activationIssues = emptyList(), error = null) }
                }
                .onFailure { e ->
                    if (e is ProgramActivationInvalidException) {
                        _state.update { it.copy(loading = false, error = null, activationIssues = e.issues) }
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

    // --- Continue a finished program (extend in place, #282) ---

    fun openContinuePicker() = _state.update { it.copy(showContinuePicker = true, error = null) }

    fun dismissContinuePicker() = _state.update { it.copy(showContinuePicker = false) }

    /**
     * Continue this program ([scope] = "WEEK" or "CYCLE"): the backend appends
     * more weeks — resuming from the last logged loads/reps — and flips it back to
     * ACTIVE. The reactive load stream re-emits the new status + schedule from the
     * mirror (the repository refreshes it), so we just close the picker.
     */
    fun continueProgram(scope: String) {
        if (_state.value.continuing) return
        viewModelScope.launch {
            _state.update { it.copy(continuing = true, error = null) }
            repository.continueProgram(programId, scope)
                .onSuccess {
                    _state.update { it.copy(continuing = false, showContinuePicker = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            continuing = false,
                            showContinuePicker = false,
                            error = e.message ?: "Couldn't continue the program",
                        )
                    }
                }
        }
    }

    // --- Edit title/description ---

    fun startEdit() = _state.update { it.copy(editing = true, error = null) }

    fun cancelEdit() = _state.update { it.copy(editing = false) }

    /** PATCH the program's metadata; closes the sheet and updates on success. */
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

    // --- Log a past session ---

    fun openPastSessions() = _state.update { it.copy(showPastSessions = true) }

    fun dismissPastSessions() = _state.update { it.copy(showPastSessions = false) }

    /** Delete a logged session: revert it to PLANNED and reload so the picker reflects it. */
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
                    _state.update { it.copy(parkedError = e.message ?: "Couldn't restore the workout") }
                }
        }
    }

    /** Give up on a parked completion (offered when the session is gone). */
    fun discardParked(parked: ParkedCompletion) {
        viewModelScope.launch {
            sessionRepository.discardParked(parked.programId, parked.scheduledId)
                .onSuccess { _state.update { it.copy(parkedError = null) } }
                .onFailure { e ->
                    _state.update { it.copy(parkedError = e.message ?: "Couldn't discard the workout") }
                }
        }
    }

    fun consumeRestoredSession() = _state.update { it.copy(restoredSession = null) }

    // --- Apply the program's nutrition guidance as the macro target ---

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
                    val weekStart = weekStartOf(today)
                    val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
                    // Best-effort, online-only nutrition guidance. Emits null first
                    // so the program + schedule render immediately, then the fetched
                    // value when it lands — never blocking the screen on this call.
                    val guidanceFlow = flow<NutritionGuidance?> {
                        emit(null)
                        emit(runCatching { repository.nutritionGuidance(programId).getOrNull() }.getOrNull())
                    }
                    combine(
                        repository.observeProgram(programId),
                        // A schedule read failing must NOT blank the whole screen.
                        repository.observeCalendar(programId, weekStart, weekEnd)
                            .catch { emit(emptyList()) },
                        repository.observeCalendar(programId, EPOCH, weekStart.minus(1, DateTimeUnit.DAY))
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

    private companion object {
        /** Wide lower bound for the "log a past session" calendar read. */
        val EPOCH = LocalDate(1970, 1, 1)
    }
}

/** The reactive [ProgramDetailViewModel] load payload. */
private data class ProgramDetailLoad(
    val program: WorkoutProgram?,
    val thisWeek: List<ScheduledWorkout>,
    val pastSessions: List<ScheduledWorkout>,
    val guidance: NutritionGuidance?,
)
