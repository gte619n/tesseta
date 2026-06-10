package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ADR-0012 (IMPL-AND-16) — the hub's session-recovery state: the newest
 * in-progress local draft (the "Resume" banner) and, per the IMPL-16 Q3
 * resolution, the newest parked completion upload (the "finished workout
 * couldn't sync — restore to review" banner). Both reactive off Room, so the
 * banners appear/disappear with their underlying rows.
 */
@HiltViewModel
class WorkoutsHubViewModel @Inject constructor(
    private val sessionRepository: WorkoutSessionRepository,
) : ViewModel() {

    val activeDraft: StateFlow<WorkoutSessionDraft?> =
        sessionRepository.observeDrafts()
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Newest parked completion upload, if any (IMPL-16 A10/Q3). */
    val parkedCompletion: StateFlow<ParkedCompletion?> =
        sessionRepository.observeParkedCompletions()
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _restoredSession = MutableStateFlow<ParkedCompletion?>(null)

    /** Set once a restore succeeded; the route opens the logger and consumes it. */
    val restoredSession: StateFlow<ParkedCompletion?> = _restoredSession.asStateFlow()

    private val _parkedError = MutableStateFlow<String?>(null)
    val parkedError: StateFlow<String?> = _parkedError.asStateFlow()

    /** Re-materialize the parked completion as a draft and open the logger. */
    fun restoreParked(parked: ParkedCompletion) {
        viewModelScope.launch {
            sessionRepository.restoreParked(parked.programId, parked.scheduledId)
                .onSuccess {
                    _parkedError.value = null
                    _restoredSession.value = parked
                }
                .onFailure { e ->
                    _parkedError.value = e.message ?: "Couldn't restore the workout"
                }
        }
    }

    /** Give up on a parked completion (offered when the session is gone). */
    fun discardParked(parked: ParkedCompletion) {
        viewModelScope.launch {
            sessionRepository.discardParked(parked.programId, parked.scheduledId)
                .onSuccess { _parkedError.value = null }
                .onFailure { e ->
                    _parkedError.value = e.message ?: "Couldn't discard the workout"
                }
        }
    }

    fun consumeRestoredSession() {
        _restoredSession.value = null
    }
}
