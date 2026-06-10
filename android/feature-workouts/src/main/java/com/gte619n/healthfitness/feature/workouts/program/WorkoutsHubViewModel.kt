package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ADR-0012 (IMPL-AND-16) — the hub's only state: the newest in-progress local
 * session draft, surfaced as a "Resume" banner. Reactive off the Room draft
 * table, so the banner appears/disappears with the draft itself.
 */
@HiltViewModel
class WorkoutsHubViewModel @Inject constructor(
    sessionRepository: WorkoutSessionRepository,
) : ViewModel() {

    val activeDraft: StateFlow<WorkoutSessionDraft?> =
        sessionRepository.observeDrafts()
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
