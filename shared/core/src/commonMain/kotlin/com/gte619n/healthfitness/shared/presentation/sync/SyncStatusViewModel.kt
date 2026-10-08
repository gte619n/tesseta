package com.gte619n.healthfitness.shared.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.sync.OutboxStore
import com.gte619n.healthfitness.shared.sync.SyncEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Settings › Sync log signals (parity with Android's `SyncStatusViewModel`):
 * queued vs. parked/failed outbox rows and the last error, plus Retry (drain) and
 * Refresh (delta pull) actions. Drives [SyncDiagnosticsView] on iOS.
 *
 * Counts are reactive off the shared outbox ([OutboxStore.pendingCount] +
 * [OutboxStore.observeParked]); failed == parked (terminal 4xx) rows. Network
 * online-state isn't modelled here (no shared reachability source yet) — the
 * client treats itself as online and the drain/pull fail gracefully offline.
 */
class SyncStatusViewModel(
    private val outbox: OutboxStore,
    private val engine: SyncEngine,
) : ViewModel() {

    data class UiState(
        val pendingCount: Int = 0,
        val failedCount: Int = 0,
        val lastError: String? = null,
    )

    val state: StateFlow<UiState> =
        combine(outbox.pendingCount(), outbox.observeParked()) { pending, parked ->
            UiState(
                pendingCount = pending,
                failedCount = parked.size,
                lastError = parked.firstNotNullOfOrNull { it.lastError },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Re-arm + drain the outbox (parity with Android's "Retry failed changes"). */
    fun retry() {
        viewModelScope.launch { engine.drainOutbox() }
    }

    /** Foreground delta pull. */
    fun refresh() {
        viewModelScope.launch { engine.pull() }
    }
}
