package com.gte619n.healthfitness.mobile.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.net.Connectivity
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.sync.SyncDiagnostics
import com.gte619n.healthfitness.data.sync.SyncEngine
import com.gte619n.healthfitness.data.sync.SyncScheduler
import com.gte619n.healthfitness.ui.sync.SyncUiState
import com.gte619n.healthfitness.ui.sync.syncUiStateOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * IMPL-AND-20 (Phase 6) — drives the global [com.gte619n.healthfitness.ui.sync.SyncStatusBar] (D11).
 *
 * Combines the Phase-5-exposed signals into a single [SyncUiState] via the pure
 * [syncUiStateOf] mapping:
 *  - [OutboxRepository.pendingCount] — total queued write count,
 *  - [OutboxRepository.failedCount] — writes that failed a replay attempt (#39),
 *  - [Connectivity.isOnline] — offline pill,
 *  - [SyncEngine.updatedElsewhere] — the lightweight "updated elsewhere" note.
 *
 * The distinct global **FAILED** "changes failed — retry" state (D11, #39) is now
 * surfaced from the failed-row count; [retry] re-drains the outbox. The per-row
 * FAILED badge (the [com.gte619n.healthfitness.ui.sync.SyncBadge]) gives the
 * precise per-item state.
 */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val outbox: OutboxRepository,
    private val syncEngine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val diagnostics: SyncDiagnostics,
    connectivity: Connectivity,
) : ViewModel() {

    // A transient "updated elsewhere" latch that auto-clears; here kept simple as
    // a sticky flag the bar shows until the next clean state.
    private val updatedElsewhere = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            syncEngine.updatedElsewhere.collect { updatedElsewhere.value = true }
        }
    }

    val state: StateFlow<SyncUiState> =
        combine(
            connectivity.isOnline,
            outbox.pendingCount(),
            outbox.failedCount(),
            updatedElsewhere,
            diagnostics.lastError,
        ) { online, pending, failed, updated, lastError ->
            syncUiStateOf(
                online = online,
                // Pending = queued rows that have not yet failed; the FAILED state
                // owns the failed rows so the bar shows "failed" not "waiting".
                pendingCount = (pending - failed).coerceAtLeast(0),
                failedCount = failed,
                syncing = false,
                updatedElsewhere = updated && pending == 0,
                detail = lastError?.shortReason(),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = syncUiStateOf(online = true, pendingCount = 0, failedCount = 0, syncing = false),
        )

    /**
     * Manual retry (D11): re-arm every FAILED row — mid-backoff or parked on a
     * terminal 4xx — then re-drain the outbox.
     */
    fun retry() {
        viewModelScope.launch {
            // Re-arm, then drain inline so a connected device gets immediate
            // feedback (success clears the banner) instead of relying solely on
            // the WorkManager enqueue, which silently no-ops while offline. The
            // enqueue stays as the offline/connectivity-regained fallback.
            runCatching { outbox.rearmFailed() }
                .onFailure { diagnostics.record("retry", it.message ?: it.javaClass.simpleName, cause = it) }
            scheduler.enqueueDrain()
            runCatching { outbox.drain() }
                .onFailure { diagnostics.record("retry-drain", it.message ?: it.javaClass.simpleName, cause = it) }
        }
        updatedElsewhere.update { false }
    }

    /** Pull-to-refresh hook (D11): a foreground delta pull. */
    fun refresh() {
        viewModelScope.launch { runCatching { syncEngine.pull() } }
        updatedElsewhere.update { false }
    }
}
