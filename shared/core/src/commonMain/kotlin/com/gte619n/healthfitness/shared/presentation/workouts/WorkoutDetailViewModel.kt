package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.WorkoutProgramRepository
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutProgram
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave D — KMP port of the Android `WorkoutDetailViewModel`.
 * Loads a single workout (one [WorkoutDay]) out of the deep program tree — the
 * program is fetched whole and the requested phase + day are located by id
 * ([phaseId] disambiguates because dayId is only unique within its phase's weekly
 * microcycle). Read-only viewer, plus "run this workout today".
 *
 * Wave-D addition vs. Android: the detail also surfaces **prior performance** per
 * exercise (the last logged sets for each prescription's exercise), so the viewer
 * shows "last time you did…" before the athlete starts. This is the last-sets
 * prefill the Android session logger uses at start-time, hoisted to the read-only
 * detail; see [priorPerformance] + [WorkoutDetailViewModelTest]. The nav args
 * become constructor params on KMP (DI wires them; on Android they came from
 * SavedStateHandle).
 */
data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val programTitle: String? = null,
    val phaseTitle: String? = null,
    val day: WorkoutDay? = null,
    /**
     * Prior performance per exercise id — the most recent logged sets from the
     * last time this exercise was performed, for the "last time" prefill hint.
     * Empty until the best-effort last-sets read lands (or when nothing was ever
     * logged / the session isn't server-persisted yet — see the last-sets-404 memo).
     */
    val priorPerformance: Map<String, List<LoggedSet>> = emptyMap(),
    val error: String? = null,
    /** True while a "run this workout today" request is in flight. */
    val starting: Boolean = false,
    /** Non-null once today's session is materialized — the route opens the logger. */
    val startedScheduledId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModel(
    private val repository: WorkoutProgramRepository,
    /** The owning program's id (public so the route can open the materialized session). */
    val programId: String,
    private val phaseId: String,
    private val dayId: String,
) : ViewModel() {

    /** Bumped by [refresh] to force a re-subscription (and a fresh network refresh). */
    private val refreshToken = MutableStateFlow(0)

    private val _state = MutableStateFlow(WorkoutDetailUiState())
    val state: StateFlow<WorkoutDetailUiState> = _state.asStateFlow()

    init {
        // Observe the deep program reactively and pick out this phase+day, so the
        // workout renders from the mirror (offline-capable) and updates in place.
        viewModelScope.launch {
            refreshToken
                .flatMapLatest {
                    _state.update { it.copy(loading = true, error = null) }
                    repository.observeProgram(programId)
                        .map<WorkoutProgram?, Result<WorkoutProgram?>> { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                }
                .collect { result ->
                    result
                        .onSuccess { program -> applyProgram(program) }
                        .onFailure { e ->
                            _state.update {
                                it.copy(loading = false, error = e.message ?: "Failed to load workout")
                            }
                        }
                }
        }
    }

    private fun applyProgram(program: WorkoutProgram?) {
        if (program == null) {
            _state.update { it.copy(loading = false, error = "Failed to load workout") }
            return
        }
        val phase = program.phases.firstOrNull { it.phaseId == phaseId }
        val day = phase?.days?.firstOrNull { it.dayId == dayId }
        when {
            day != null && phase != null -> {
                _state.update {
                    it.copy(
                        loading = false,
                        programTitle = program.title,
                        phaseTitle = phase.title,
                        day = day,
                        error = null,
                    )
                }
                loadPriorPerformance(day)
            }
            // A shallow row still upgrading to its deep tree has no phases/days yet
            // — keep loading rather than flash "not found".
            program.phases.isEmpty() || phase?.days?.isEmpty() == true ->
                _state.update { it.copy(loading = true, error = null) }
            else ->
                _state.update { it.copy(loading = false, error = "Workout not found") }
        }
    }

    /**
     * Best-effort fetch of the last logged sets for every exercise in [day], for
     * the "last time you did…" prefill. Online-only; a failure leaves the map as
     * it is (the hint just doesn't render). Never blocks the workout from showing.
     */
    private fun loadPriorPerformance(day: WorkoutDay) {
        val exerciseIds = day.blocks
            .flatMap { it.prescriptions }
            .mapNotNull { it.exercise?.exerciseId }
            .distinct()
        if (exerciseIds.isEmpty()) return
        viewModelScope.launch {
            val prior = mutableMapOf<String, List<LoggedSet>>()
            exerciseIds.forEach { id ->
                repository.lastSetsFor(programId, id).onSuccess { sets ->
                    if (sets.isNotEmpty()) prior[id] = sets
                }
            }
            if (prior.isNotEmpty()) {
                _state.update { it.copy(priorPerformance = it.priorPerformance + prior) }
            }
        }
    }

    fun refresh() = refreshToken.update { it + 1 }

    /**
     * Materialize this workout as a session dated today and open the logger — the
     * "run any workout as today" path that works even after the program's window
     * has elapsed. Offline-first; a failure surfaces inline without leaving.
     */
    fun startToday() {
        if (_state.value.starting) return
        viewModelScope.launch {
            _state.update { it.copy(starting = true, error = null) }
            repository.runDayToday(programId, phaseId, dayId)
                .onSuccess { scheduledId ->
                    _state.update { it.copy(starting = false, startedScheduledId = scheduledId) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(starting = false, error = e.message ?: "Couldn't start this workout")
                    }
                }
        }
    }

    /** Clear the one-shot navigation signal once the route has consumed it. */
    fun consumeStarted() = _state.update { it.copy(startedScheduledId = null) }
}
