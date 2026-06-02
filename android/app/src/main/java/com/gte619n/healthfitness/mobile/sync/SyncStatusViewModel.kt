package com.gte619n.healthfitness.mobile.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.net.Connectivity
import com.gte619n.healthfitness.data.sync.OutboxRepository
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
 * Combines the three Phase-5-exposed signals into a single [SyncUiState] via the
 * pure [syncUiStateOf] mapping:
 *  - [OutboxRepository.pendingCount] — queued/failed write count,
 *  - [Connectivity.isOnline] — offline pill,
 *  - [SyncEngine.updatedElsewhere] — the lightweight "updated elsewhere" note.
 *
 * Note: the outbox surfaces a single pending count (PENDING and FAILED rows are
 * both "not yet synced"); we treat any pending as PENDING and reserve the FAILED
 * indicator for an explicit signal. A coarser-but-honest mapping; per-row FAILED
 * badges (the [com.gte619n.healthfitness.ui.sync.SyncBadge]) give the precise
 * per-item state.
 */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val outbox: OutboxRepository,
    private val syncEngine: SyncEngine,
    private val scheduler: SyncScheduler,
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
            updatedElsewhere,
        ) { online, pending, updated ->
            syncUiStateOf(
                online = online,
                pendingCount = pending,
                failedCount = 0,
                syncing = false,
                updatedElsewhere = updated && pending == 0,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = syncUiStateOf(online = true, pendingCount = 0, failedCount = 0, syncing = false),
        )

    /** Manual retry (D11): re-drain the outbox (FAILED rows are retried). */
    fun retry() {
        scheduler.enqueueDrain()
        updatedElsewhere.update { false }
    }

    /** Pull-to-refresh hook (D11): a foreground delta pull. */
    fun refresh() {
        viewModelScope.launch { runCatching { syncEngine.pull() } }
        updatedElsewhere.update { false }
    }
}
