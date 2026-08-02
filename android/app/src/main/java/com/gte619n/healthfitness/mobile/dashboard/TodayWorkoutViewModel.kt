package com.gte619n.healthfitness.mobile.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.dashboard.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlin.math.roundToInt

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
        /** When the session started — the card shows live (paused-aware) elapsed. */
        val startedAt: Instant,
    ) : TodayWorkout

    /**
     * A planned session ready to start in one tap. [isToday] is false when it's
     * the next upcoming (or a missed earlier) session rather than today's, so
     * the card can read "Start next workout".
     */
    data class Start(
        val programId: String,
        val scheduledId: String,
        val label: String?,
        val isToday: Boolean,
    ) : TodayWorkout

    /**
     * Today's session is already done — the card shows a recap (volume, time,
     * sets, calorie estimate) instead of an action, and taps into the session
     * review. This wins over [Start] on the day of completion: right after
     * finishing, the user wants to see what they did, not a prompt for tomorrow.
     */
    data class Completed(
        val programId: String,
        val scheduledId: String,
        val label: String?,
        /** Total seconds from start to finish (server-stamped at finish). */
        val durationSeconds: Int?,
        /** Count of all logged sets across every prescription. */
        val totalSets: Int,
        /** Sum of weight × reps across all logged sets, in pounds. */
        val totalWeightLbs: Double,
        /** MET-based burn estimate; null when there's no duration to estimate from. */
        val estimatedCalories: Int?,
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
    private val bodyComp: DashboardBodyCompositionRepository,
) : ViewModel() {

    // The calendar-resolved pick for today: either a recap of the session already
    // completed today ([TodayWorkout.Completed]) or the next session to start
    // ([TodayWorkout.Start]). Null until refresh() resolves it (or on a rest day
    // with nothing to start).
    private val todaySession = MutableStateFlow<TodayWorkout?>(null)

    val state: StateFlow<TodayWorkout> =
        combine(sessionRepository.observeDrafts(), todaySession) { drafts, resolved ->
            // Only a genuinely in-progress workout resumes here; a draft opened
            // over an already-finished session (review) shouldn't read as
            // "Resume" on the home screen — it falls through to the resolved pick.
            val draft = drafts.firstOrNull { it.scheduled.status == ScheduledStatus.PLANNED }
            when {
                draft != null -> TodayWorkout.Resume(
                    programId = draft.programId,
                    scheduledId = draft.scheduledId,
                    label = draft.scheduled.dayLabel.ifBlank { null },
                    setsLogged = draft.totalLoggedSets,
                    startedAt = draft.startedAt,
                )
                resolved != null -> resolved
                else -> TodayWorkout.Hidden
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayWorkout.Hidden)

    /**
     * Resolve what the card should show from the active program's current-week
     * calendar. Today's completed session wins (recap): the user just finished
     * and wants their stats. Otherwise offer today's planned session, or the next
     * upcoming (or soonest missed) one so you can start a workout even on a rest day.
     */
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
            val calendar = programRepository
                .calendar(active.programId, weekStart, weekStart.plusDays(6))
                .getOrNull()
                .orEmpty()

            val completedToday = calendar
                .firstOrNull { it.date == today && it.status == ScheduledStatus.COMPLETED }
            if (completedToday != null) {
                todaySession.value = buildCompleted(active.programId, completedToday)
                return@launch
            }

            val planned = calendar.filter { it.status == ScheduledStatus.PLANNED }
            val chosen = planned.firstOrNull { it.date == today }
                ?: planned.filter { !it.date.isBefore(today) }.minByOrNull { it.date }
                ?: planned.minByOrNull { it.date }
            todaySession.value = chosen?.let {
                TodayWorkout.Start(
                    programId = active.programId,
                    scheduledId = it.scheduledId,
                    label = it.dayLabel.ifBlank { null },
                    isToday = it.date == today,
                )
            }
        }
    }

    /** Build the recap from a completed session's logged sets + duration. */
    private suspend fun buildCompleted(
        programId: String,
        session: ScheduledWorkout,
    ): TodayWorkout.Completed {
        val prescriptions = session.session?.blocks?.flatMap { it.prescriptions }.orEmpty()
        val sets = prescriptions.sumOf { it.loggedSets.size }
        val volume = prescriptions.sumOf { rx ->
            rx.loggedSets.sumOf { (it.weightLbs ?: 0.0) * (it.reps ?: 0) }
        }
        // Cached (offline) latest bodyweight feeds the MET-based estimate; falls
        // back to a default when body composition hasn't synced yet.
        val bodyWeightLb = runCatching { bodyComp.cachedRecent() }.getOrNull()?.latestLb
        return TodayWorkout.Completed(
            programId = programId,
            scheduledId = session.scheduledId,
            label = session.dayLabel.ifBlank { null },
            durationSeconds = session.durationSeconds,
            totalSets = sets,
            totalWeightLbs = volume,
            estimatedCalories = estimateCalories(session.durationSeconds, bodyWeightLb),
        )
    }

    /**
     * Rough resistance-training burn: kcal = MET × 3.5 × kg / 200 × minutes
     * (the ACSM per-minute formula). A single moderate-vigorous MET keeps this an
     * honest ballpark — it's labelled "est." in the UI — rather than a precise claim.
     */
    private fun estimateCalories(durationSeconds: Int?, bodyWeightLb: Double?): Int? {
        if (durationSeconds == null || durationSeconds <= 0) return null
        val kg = (bodyWeightLb ?: DEFAULT_BODY_WEIGHT_LB) * LB_TO_KG
        val minutes = durationSeconds / 60.0
        return (STRENGTH_MET * 3.5 * kg / 200.0 * minutes).roundToInt()
    }

    private companion object {
        const val LB_TO_KG = 0.453592
        /** Compendium MET for moderate-vigorous resistance training. */
        const val STRENGTH_MET = 5.0
        /** Used only when no bodyweight has synced yet, so a rough estimate still shows. */
        const val DEFAULT_BODY_WEIGHT_LB = 175.0
    }
}
