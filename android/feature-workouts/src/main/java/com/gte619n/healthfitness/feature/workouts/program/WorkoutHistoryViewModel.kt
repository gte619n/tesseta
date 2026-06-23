package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only Workout History: COMPLETED sessions across all programs, newest
 * first, with the logged sets to review. Paged — [load] fetches the first page
 * and [loadMore] appends the next as the list scrolls. Online-only (not
 * mirrored); a failed first load offers a retry.
 */
@HiltViewModel
class WorkoutHistoryViewModel @Inject constructor(
    private val repository: WorkoutProgramRepository,
    private val sessionRepository: WorkoutSessionRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val sessions: List<ScheduledWorkout> = emptyList(),
        val error: String? = null,
        /** A further page is appending (footer spinner). */
        val loadingMore: Boolean = false,
        /** Another page exists — drives load-on-scroll and the footer. */
        val hasMore: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Next page to request; advanced only after a page lands so a failed/raced
    // load doesn't skip rows.
    private var nextPage = 0

    init {
        load()
    }

    /** (Re)load from the first page, replacing whatever is shown. */
    fun load() {
        nextPage = 0
        viewModelScope.launch {
            _state.update { State(loading = true) }
            repository.workoutHistoryPage(0, PAGE_SIZE)
                .onSuccess { page ->
                    nextPage = 1
                    _state.update {
                        State(loading = false, sessions = page.items, hasMore = page.hasMore)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        State(loading = false, error = e.message ?: "Couldn't load workout history")
                    }
                }
        }
    }

    /** Append the next page; no-op while one is in flight or none remain. */
    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || !s.hasMore || s.loading) return
        val page = nextPage
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            repository.workoutHistoryPage(page, PAGE_SIZE)
                .onSuccess { result ->
                    nextPage = page + 1
                    _state.update {
                        it.copy(
                            loadingMore = false,
                            sessions = it.sessions + result.items,
                            hasMore = result.hasMore,
                        )
                    }
                }
                // Keep hasMore so scrolling can retry; just stop the spinner.
                .onFailure { _state.update { it.copy(loadingMore = false) } }
        }
    }

    /**
     * Delete a session from history: revert it to PLANNED server-side (clears the
     * actuals and the fanned-out workout) and drop it from the list optimistically.
     * On failure, reload so the row reappears rather than vanishing silently.
     */
    fun deleteSession(session: ScheduledWorkout) {
        viewModelScope.launch {
            _state.update { st ->
                st.copy(sessions = st.sessions.filterNot { it.scheduledId == session.scheduledId })
            }
            sessionRepository.reset(session.programId, session.scheduledId)
                .onFailure { e ->
                    _state.update { it.copy(error = e.message ?: "Couldn't delete the workout") }
                    load()
                }
        }
    }

    private companion object {
        const val PAGE_SIZE = 25
    }
}
