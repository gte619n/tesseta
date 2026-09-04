package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.data.workouts.settings.WorkoutSettingsRepository
import com.gte619n.healthfitness.domain.workouts.WorkoutStreakSettings
import com.gte619n.healthfitness.domain.workouts.program.ProgramActivationInvalidException
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/**
 * The "This Week" landing state. Unlike [ProgramDetailViewModel] (keyed by a nav
 * arg), this resolves the *featured* program reactively — the active one, else
 * the most recently touched ([resolveActiveProgram]) — then observes its deep
 * tree and calendar. Compliance ([monthDays]) and [streak] are derived
 * client-side ([ComplianceMath]); session-recovery banners are folded in from
 * the former WorkoutsHubViewModel.
 */
data class WorkoutsLandingUiState(
    val loading: Boolean = true,
    /** The featured program (deep). Null → no program to show ([hasAnyProgram] disambiguates). */
    val program: WorkoutProgram? = null,
    /** Current-week scheduled sessions (Mon–Sun of [today]). */
    val thisWeek: List<ScheduledWorkout> = emptyList(),
    /** Scheduled sessions within [visibleMonth], for the compliance grid. */
    val monthDays: List<ScheduledWorkout> = emptyList(),
    val visibleMonth: YearMonth = YearMonth.now(),
    /** Consecutive weeks that each met [weeklyStreakTarget] completed workouts. */
    val weekStreak: Int = 0,
    /** Completed workouts logged so far in the current (Mon–today) week. */
    val completedThisWeek: Int = 0,
    /** Weekly completed-workout target that keeps the streak alive (from settings). */
    val weeklyStreakTarget: Int = WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET,
    /** Materialized sessions on/before today, newest first — the past-workouts pool. */
    val pastSessions: List<ScheduledWorkout> = emptyList(),
    val showPastSessions: Boolean = false,
    /** False only when the user has no programs at all (drives the two empty states). */
    val hasAnyProgram: Boolean = true,
    val activeDraft: WorkoutSessionDraft? = null,
    val parkedCompletion: ParkedCompletion? = null,
    val parkedError: String? = null,
    val restoredSession: ParkedCompletion? = null,
    /** Validation issues from a failed activation (422); shown inline. */
    val activationIssues: List<String> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkoutsLandingViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
    private val sessionRepository: WorkoutSessionRepository,
    private val settingsRepository: WorkoutSettingsRepository,
) : ViewModel() {

    /** Overridable in tests so the "this week" range and streak are deterministic. */
    var today: LocalDate = LocalDate.now()

    private val refreshToken = MutableStateFlow(0)

    /** Null until the user navigates months; the load defaults it to [today]'s month. */
    private val visibleMonth = MutableStateFlow<YearMonth?>(null)

    /** The featured program's id, for filtering the session-recovery banners. */
    private val resolvedProgramId = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(WorkoutsLandingUiState())
    val state: StateFlow<WorkoutsLandingUiState> = _state.asStateFlow()

    init {
        load()
        // Sync the weekly streak target from the backend into the DataStore cache.
        // The reactive [settingsRepository.weeklyStreakTarget] flow the load
        // observes emits the fresh value once it lands, recomputing the streak.
        viewModelScope.launch { settingsRepository.refresh() }
        // The active local draft + newest parked upload for the featured program
        // drive the Resume / recovery banners; both reactive off Room.
        viewModelScope.launch {
            combine(sessionRepository.observeDrafts(), resolvedProgramId) { drafts, pid ->
                drafts.firstOrNull { it.programId == pid }
            }.collect { draft -> _state.update { it.copy(activeDraft = draft) } }
        }
        viewModelScope.launch {
            combine(sessionRepository.observeParkedCompletions(), resolvedProgramId) { parked, pid ->
                parked.firstOrNull { it.programId == pid }
            }.collect { p -> _state.update { it.copy(parkedCompletion = p) } }
        }
    }

    fun refresh() = refreshToken.update { it + 1 }

    fun prevMonth() = visibleMonth.update { (it ?: YearMonth.from(today)).minusMonths(1) }

    fun nextMonth() = visibleMonth.update { (it ?: YearMonth.from(today)).plusMonths(1) }

    fun openPastSessions() = _state.update { it.copy(showPastSessions = true) }

    fun dismissPastSessions() = _state.update { it.copy(showPastSessions = false) }

    /** Activate (or re-materialize) the featured program; the reactive load re-emits. */
    fun activate() {
        val programId = _state.value.program?.programId ?: return
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

    /**
     * Delete a logged session: revert it to PLANNED (clears the actuals) and
     * refresh so the past-workouts picker reflects the change.
     */
    fun deleteSession(scheduledId: String) {
        val programId = _state.value.program?.programId ?: return
        viewModelScope.launch {
            sessionRepository.reset(programId, scheduledId)
                .onSuccess { refresh() }
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

    private fun load() {
        viewModelScope.launch {
            val loads = combine(refreshToken, visibleMonth) { _, month -> month }
                .flatMapLatest { navMonth ->
                    val month = navMonth ?: YearMonth.from(today)
                    _state.update {
                        it.copy(loading = true, error = null, visibleMonth = month, today = today)
                    }
                    repository.observePrograms()
                        .flatMapLatest { programs ->
                            val resolved = resolveActiveProgram(programs)
                            resolvedProgramId.value = resolved?.programId
                            if (resolved == null) {
                                flowOf(LandingLoad(null, programs.isNotEmpty(), emptyList(), month))
                            } else {
                                val monthStart = month.atDay(1)
                                val monthEnd = month.atEndOfMonth()
                                // One calendar read covering the visible month AND enough
                                // history for the streak (back to the program start).
                                val calFrom = minOf(resolved.startDate ?: monthStart, monthStart)
                                val calTo = maxOf(today, monthEnd)
                                // The streak counts completed sessions across every
                                // program, so it must not reset when a new program
                                // starts. A wide look-back comfortably covers any
                                // realistic weekly run.
                                val streakFrom = today.minusYears(2)
                                combine(
                                    repository.observeProgram(resolved.programId),
                                    repository.observeCalendar(resolved.programId, calFrom, calTo)
                                        .catch { emit(emptyList()) },
                                    repository.observeAllCompleted(streakFrom, today)
                                        .catch { emit(emptyList()) },
                                ) { deep, cal, allCompleted ->
                                    LandingLoad(deep ?: resolved, true, cal, month, allCompleted)
                                }
                            }
                        }
                        .map { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                }
            // Fold in the weekly streak target so the streak recomputes both on a
            // calendar change and on a settings change (local save or sync push).
            combine(loads, settingsRepository.weeklyStreakTarget) { result, target -> result to target }
                .collect { (result, target) ->
                    result
                        .onSuccess { applyLoad(it, target) }
                        .onFailure { e ->
                            _state.update {
                                it.copy(loading = false, error = e.message ?: "Failed to load your training")
                            }
                        }
                }
        }
    }

    private fun applyLoad(data: LandingLoad, weeklyTarget: Int) {
        val program = data.program
        if (program == null) {
            _state.update {
                it.copy(
                    loading = false,
                    program = null,
                    hasAnyProgram = data.hasAnyProgram,
                    thisWeek = emptyList(),
                    monthDays = emptyList(),
                    pastSessions = emptyList(),
                    weekStreak = 0,
                    completedThisWeek = 0,
                    weeklyStreakTarget = weeklyTarget,
                    visibleMonth = data.month,
                    error = null,
                )
            }
            return
        }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val cal = data.calendar
        // The streak + weekly-progress count from completed sessions across ALL
        // programs (so a new program keeps a live streak); the calendar grid,
        // this-week list and history stay scoped to the featured program. Fall
        // back to the per-program calendar if the cross-program read came back
        // empty (e.g. kill-switch degradation).
        val streakSource = data.allCompleted.ifEmpty { cal }
        _state.update {
            it.copy(
                loading = false,
                program = program,
                hasAnyProgram = true,
                visibleMonth = data.month,
                thisWeek = cal.filter { s -> s.date in weekStart..weekEnd }.sortedBy { s -> s.date },
                monthDays = cal.filter { s -> YearMonth.from(s.date) == data.month },
                pastSessions = cal.filter { s -> s.date <= today }.sortedByDescending { s -> s.date },
                weekStreak = computeWeeklyStreak(streakSource, today, weeklyTarget),
                completedThisWeek = completedThisWeek(streakSource, today),
                weeklyStreakTarget = weeklyTarget,
                error = null,
            )
        }
    }
}

/** The reactive [WorkoutsLandingViewModel.load] payload for one (month) window. */
private data class LandingLoad(
    val program: WorkoutProgram?,
    val hasAnyProgram: Boolean,
    val calendar: List<ScheduledWorkout>,
    val month: YearMonth,
    // Completed sessions across ALL programs (not just the active one), so the
    // weekly streak carries over when the user starts a new program.
    val allCompleted: List<ScheduledWorkout> = emptyList(),
)
