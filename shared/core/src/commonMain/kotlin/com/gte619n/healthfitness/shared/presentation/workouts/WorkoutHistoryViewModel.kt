package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.WorkoutProgramRepository
import com.gte619n.healthfitness.shared.data.WorkoutSessionRepository
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave D — KMP port of the Android `WorkoutHistoryViewModel`.
 * Read-only Workout History: COMPLETED sessions across all programs, newest
 * first. Paged — [load] fetches the first page, [loadMore] appends as the list
 * scrolls. Online-only; ADR-0018 shows a cached first page instantly on re-entry
 * (no spinner) and revalidates underneath.
 */
class WorkoutHistoryViewModel(
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
        val cached = repository.cachedFirstHistoryPage()
        viewModelScope.launch {
            _state.update {
                if (cached != null) {
                    State(loading = false, sessions = cached.items, hasMore = cached.hasMore)
                } else {
                    State(loading = true)
                }
            }
            repository.workoutHistoryPage(0, PAGE_SIZE)
                .onSuccess { page ->
                    nextPage = 1
                    _state.update {
                        State(loading = false, sessions = page.items, hasMore = page.hasMore)
                    }
                }
                .onFailure { e ->
                    _state.update { current ->
                        // A failed revalidation must not replace a cached page with
                        // an error (ADR-0018); only a cold load surfaces the error.
                        if (cached != null) {
                            current.copy(loading = false)
                        } else {
                            State(loading = false, error = e.message ?: "Couldn't load workout history")
                        }
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
     * Delete a session from history: revert it to PLANNED server-side and drop it
     * from the list optimistically. On failure, reload so the row reappears.
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
