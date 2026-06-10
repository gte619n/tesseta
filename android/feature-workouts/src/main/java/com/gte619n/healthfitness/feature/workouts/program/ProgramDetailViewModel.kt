package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
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
    /** In-progress local session draft for THIS program (resume affordance). */
    val activeDraft: WorkoutSessionDraft? = null,
    /** Device date, fixed by the VM so the "start today" affordance is testable. */
    val today: LocalDate = LocalDate.now(),
    val error: String? = null,
)

@HiltViewModel
class ProgramDetailViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
    sessionRepository: WorkoutSessionRepository,
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
    }

    fun refresh() = load()

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

            val deepResult = deepDeferred.await()
            val calendarResult = calendarDeferred.await()

            deepResult
                .onSuccess { program ->
                    val week = calendarResult.getOrDefault(emptyList())
                        .sortedBy { it.date }
                    _state.update {
                        it.copy(
                            loading = false,
                            program = program,
                            thisWeek = week,
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
