package com.gte619n.healthfitness.mobile.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** What the home "Today's workout" card should offer right now. */
sealed interface TodayWorkout {
    /** No in-progress draft and nothing planned for today — render nothing. */
    data object Hidden : TodayWorkout

    /** An in-progress local draft can be resumed in one tap (ADR-0012 D1). */
    data class Resume(
        val programId: String,
        val scheduledId: String,
        val label: String?,
        val setsLogged: Int,
    ) : TodayWorkout

    /** Today has a planned session ready to start in one tap. */
    data class Start(
        val programId: String,
        val scheduledId: String,
        val label: String?,
    ) : TodayWorkout
}

/**
 * Backs the home [TodayWorkoutCard]. The in-progress draft is reactive off Room
 * (so resume appears the moment a session is parked); today's planned session is
 * a best-effort lookup of the active program's calendar, refreshed on resume.
 * Resume always wins over Start — you finish what you started before the next.
 */
@HiltViewModel
class TodayWorkoutViewModel @Inject constructor(
    private val sessionRepository: WorkoutSessionRepository,
    private val programRepository: WorkoutProgramRepository,
) : ViewModel() {

    private val todaySession = MutableStateFlow<TodayWorkout.Start?>(null)

    val state: StateFlow<TodayWorkout> =
        combine(sessionRepository.observeDrafts(), todaySession) { drafts, planned ->
            val draft = drafts.firstOrNull()
            when {
                draft != null -> TodayWorkout.Resume(
                    programId = draft.programId,
                    scheduledId = draft.scheduledId,
                    label = draft.scheduled.dayLabel.ifBlank { null },
                    setsLogged = draft.totalLoggedSets,
                )
                planned != null -> planned
                else -> TodayWorkout.Hidden
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayWorkout.Hidden)

    /** Resolve today's planned session from the active program's current-week calendar. */
    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val active = programRepository.list().getOrNull()
                ?.firstOrNull { it.status == ProgramStatus.ACTIVE }
            if (active == null) {
                todaySession.value = null
                return@launch
            }
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sessions = programRepository
                .calendar(active.programId, weekStart, weekStart.plusDays(6))
                .getOrNull()
                .orEmpty()
            val todays = sessions.firstOrNull {
                it.date == today && it.status == ScheduledStatus.PLANNED
            }
            todaySession.value = todays?.let {
                TodayWorkout.Start(active.programId, it.scheduledId, it.dayLabel.ifBlank { null })
            }
        }
    }
}
