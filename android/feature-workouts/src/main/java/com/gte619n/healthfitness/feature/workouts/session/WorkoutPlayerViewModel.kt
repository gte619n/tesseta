package com.gte619n.healthfitness.feature.workouts.session

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workout.ExerciseRepository
import com.gte619n.healthfitness.data.workout.WorkoutRepository
import com.gte619n.healthfitness.domain.workout.Exercise
import com.gte619n.healthfitness.domain.workout.LoggedSet
import com.gte619n.healthfitness.domain.workout.PlayerStep
import com.gte619n.healthfitness.domain.workout.SessionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max

enum class PlayerPhase { Loading, Working, Resting, Paused, Finishing }

data class WorkoutPlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val steps: List<PlayerStep> = emptyList(),
    val index: Int = 0,
    val phase: PlayerPhase = PlayerPhase.Loading,
    val currentExercise: Exercise? = null,
    val secondsRemaining: Int? = null,
    val runningVolume: Double = 0.0,
    val setsCompleted: Int = 0,
    val totalSets: Int = 0,
    val finishedSessionId: String? = null,
    val error: String? = null,
) {
    val currentStep: PlayerStep? get() = steps.getOrNull(index)
}

@HiltViewModel
class WorkoutPlayerViewModel @Inject constructor(
    private val repository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutPlayerUiState())
    val state: StateFlow<WorkoutPlayerUiState> = _state.asStateFlow()

    private lateinit var sessionId: String
    private val logged = mutableMapOf<String, LoggedSet>()
    private val exerciseCache = mutableMapOf<String, Exercise>()
    private var timerJob: Job? = null
    private var loaded = false

    fun load(id: String) {
        if (loaded) return
        loaded = true
        sessionId = id
        viewModelScope.launch {
            try {
                val session = repository.get(id)
                val steps = SessionEngine.steps(session)
                logged.putAll(session.loggedSets)
                val totalSets = steps.count { it is PlayerStep.PerformSet }
                _state.update {
                    it.copy(
                        loading = false,
                        title = session.title,
                        steps = steps,
                        totalSets = totalSets,
                        setsCompleted = logged.values.count { l -> l.completed },
                        runningVolume = SessionEngine.volume(logged),
                    )
                }
                // Resume exactly where we were if state was preserved across a
                // config change / process death (SavedStateHandle); otherwise
                // resume at the first not-completed set.
                val savedIndex = savedStateHandle.get<Int>(KEY_INDEX)
                val resumeIndex = savedIndex ?: SessionEngine.resumeIndex(steps, logged)
                goTo(resumeIndex, resume = savedIndex != null)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load workout") }
            }
        }
    }

    // ---- navigation between steps ----

    // `resume` = we are re-entering this exact step after recreation and should
    // continue any in-flight countdown from its persisted deadline, not restart
    // it. Normal forward/back navigation passes resume = false (fresh timer).
    private fun goTo(idx: Int, resume: Boolean = false) {
        timerJob?.cancel()
        val steps = _state.value.steps
        if (idx >= steps.size) {
            finish()
            return
        }
        savedStateHandle[KEY_INDEX] = idx
        if (!resume) clearTimerState()
        _state.update { it.copy(index = idx, error = null) }

        when (val step = steps[idx]) {
            is PlayerStep.PerformSet -> {
                loadExercise(step.exercise.exerciseId)
                if (step.set.isTimed) {
                    startOrResumeTimer(step.set.targetSeconds ?: 0, PlayerPhase.Working, resume) {
                        onTimedSetExpired()
                    }
                } else {
                    clearTimerState()
                    _state.update { it.copy(phase = PlayerPhase.Working, secondsRemaining = null) }
                }
            }
            is PlayerStep.Rest -> {
                loadExercise(step.upNext.exercise.exerciseId)
                startOrResumeTimer(step.seconds, PlayerPhase.Resting, resume) { advance() }
            }
        }
    }

    fun advance() = goTo(_state.value.index + 1)

    fun previous() {
        val prev = (_state.value.index - 1).coerceAtLeast(0)
        goTo(prev)
    }

    fun skipRest() {
        if (_state.value.currentStep is PlayerStep.Rest) advance()
    }

    fun addRestTime(seconds: Int) {
        // Push the deadline out; the tick loop reflects it on the next update.
        val deadline = savedStateHandle.get<Long>(KEY_DEADLINE)
        if (deadline != null) {
            savedStateHandle[KEY_DEADLINE] = deadline + seconds * 1000L
        }
        _state.update { it.copy(secondsRemaining = (it.secondsRemaining ?: 0) + seconds) }
    }

    // ---- set logging ----

    fun logCurrentSet(reps: Int?, weight: Double?) {
        val step = currentPerformSet() ?: return
        record(step.set.setId, reps, weight)
        advance()
    }

    fun onTimedSetExpired() {
        val step = currentPerformSet() ?: return
        record(step.set.setId, null, step.set.targetWeight)
        advance()
    }

    private fun record(setId: String, reps: Int?, weight: Double?) {
        // Optimistic local update; the network write is best-effort and never
        // blocks the workout (errors are swallowed — completion reconciles).
        logged[setId] = LoggedSet(setId, reps, weight, completed = true, loggedAt = null)
        _state.update {
            it.copy(
                runningVolume = SessionEngine.volume(logged),
                setsCompleted = logged.values.count { l -> l.completed },
            )
        }
        viewModelScope.launch {
            try {
                repository.logSet(sessionId, setId, reps, weight, true)
            } catch (_: Exception) {
                // Keep the local log; the complete() call will reconcile.
            }
        }
    }

    // ---- pause / resume ----

    fun togglePause() {
        val s = _state.value
        if (s.phase == PlayerPhase.Paused) {
            // Resume from where we paused.
            val step = s.currentStep
            val pausedRemaining = savedStateHandle.get<Int>(KEY_PAUSED)
            when {
                step is PlayerStep.Rest -> {
                    resumeFromPaused(pausedRemaining ?: (s.secondsRemaining ?: step.seconds), PlayerPhase.Resting) { advance() }
                }
                step is PlayerStep.PerformSet && step.set.isTimed -> {
                    resumeFromPaused(pausedRemaining ?: (s.secondsRemaining ?: (step.set.targetSeconds ?: 0)), PlayerPhase.Working) { onTimedSetExpired() }
                }
                else -> _state.update { it.copy(phase = PlayerPhase.Working) }
            }
        } else {
            // Pause: stop ticking and persist the remaining seconds so a later
            // recreation resumes paused at the same value (not a fresh timer).
            timerJob?.cancel()
            val remaining = s.secondsRemaining
            if (remaining != null) {
                savedStateHandle[KEY_PAUSED] = remaining
                savedStateHandle[KEY_DEADLINE] = null
            }
            _state.update { it.copy(phase = PlayerPhase.Paused) }
        }
    }

    private fun resumeFromPaused(remaining: Int, runningPhase: PlayerPhase, onZero: () -> Unit) {
        savedStateHandle[KEY_PAUSED] = null
        val deadline = SystemClock.elapsedRealtime() + remaining * 1000L
        savedStateHandle[KEY_DEADLINE] = deadline
        _state.update { it.copy(phase = runningPhase, secondsRemaining = remaining) }
        tickToDeadline(onZero)
    }

    // ---- finishing ----

    fun finishNow() = finish()

    private fun finish() {
        timerJob?.cancel()
        clearTimerState()
        _state.update { it.copy(phase = PlayerPhase.Finishing) }
        viewModelScope.launch {
            try {
                repository.complete(sessionId)
                _state.update { it.copy(finishedSessionId = sessionId) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't finish workout") }
            }
        }
    }

    // ---- timer (deadline-anchored, survives recomposition / process death) ----

    private fun startOrResumeTimer(
        totalSeconds: Int,
        runningPhase: PlayerPhase,
        resume: Boolean,
        onZero: () -> Unit,
    ) {
        val now = SystemClock.elapsedRealtime()
        val pausedRemaining = savedStateHandle.get<Int>(KEY_PAUSED)
        val savedDeadline = savedStateHandle.get<Long>(KEY_DEADLINE)

        // Re-entering a step that was paused when the process died → stay paused.
        if (resume && pausedRemaining != null) {
            _state.update { it.copy(phase = PlayerPhase.Paused, secondsRemaining = pausedRemaining) }
            return
        }

        val deadline: Long
        if (resume && savedDeadline != null) {
            deadline = savedDeadline
        } else {
            deadline = now + totalSeconds * 1000L
            savedStateHandle[KEY_DEADLINE] = deadline
        }
        savedStateHandle[KEY_PAUSED] = null

        val remaining = max(0, ceil((deadline - now) / 1000.0).toInt())
        _state.update { it.copy(phase = runningPhase, secondsRemaining = remaining) }
        if (remaining <= 0) {
            clearTimerState()
            onZero()
            return
        }
        tickToDeadline(onZero)
    }

    // Recompute remaining from the wall clock on each tick so the countdown is
    // drift-free and correct even if the coroutine was descheduled (background).
    private fun tickToDeadline(onZero: () -> Unit) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val deadline = savedStateHandle.get<Long>(KEY_DEADLINE) ?: break
                val remaining = ceil((deadline - SystemClock.elapsedRealtime()) / 1000.0).toInt()
                if (remaining <= 0) {
                    _state.update { it.copy(secondsRemaining = 0) }
                    break
                }
                _state.update { it.copy(secondsRemaining = remaining) }
                delay(250)
            }
            clearTimerState()
            onZero()
        }
    }

    private fun clearTimerState() {
        savedStateHandle[KEY_DEADLINE] = null
        savedStateHandle[KEY_PAUSED] = null
    }

    // ---- helpers ----

    private fun currentPerformSet(): PlayerStep.PerformSet? =
        _state.value.currentStep as? PlayerStep.PerformSet

    private fun loadExercise(exerciseId: String) {
        exerciseCache[exerciseId]?.let { ex ->
            _state.update { it.copy(currentExercise = ex) }
            return
        }
        _state.update { it.copy(currentExercise = null) }
        viewModelScope.launch {
            try {
                val ex = exerciseRepository.get(exerciseId)
                exerciseCache[exerciseId] = ex
                if (currentExerciseId() == exerciseId) {
                    _state.update { it.copy(currentExercise = ex) }
                }
            } catch (_: Exception) {
                // No demo — the screen falls back to a placeholder.
            }
        }
    }

    private fun currentExerciseId(): String? = when (val step = _state.value.currentStep) {
        is PlayerStep.PerformSet -> step.exercise.exerciseId
        is PlayerStep.Rest -> step.upNext.exercise.exerciseId
        null -> null
    }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val KEY_INDEX = "player_index"
        const val KEY_DEADLINE = "player_deadline_elapsed_ms"
        const val KEY_PAUSED = "player_paused_remaining_sec"
    }
}
