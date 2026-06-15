package com.gte619n.healthfitness.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-STAB (Workstream D) — a minimal in-process signal that "the user just
 * wrote something locally", scoped to cache invalidation.
 *
 * Mirror-backed screens already update instantly: they observe Room Flows, so an
 * optimistic [MirrorRepositorySupport] write recomposes them. The gap is the
 * screens that are NOT mirror-backed — the dashboard "recent activity" feed (a
 * server-derived aggregate cached in a single DataStore slot) and the not-yet-
 * migrated nutrition/goals reads. Those fetch once and only re-fetch on a TTL or
 * an explicit resume, so logging a dose or a meal didn't visibly update them.
 *
 * Every optimistic local write emits the affected mirror table here; non-reactive
 * consumers (e.g. the dashboard ViewModel) collect it and force a refresh. This
 * is deliberately a thin invalidation hint, not a general pub/sub bus — the Room
 * mirror remains the source of truth for reactive reads.
 */
@Singleton
class LocalWriteBus @Inject constructor() {
    // extraBufferCapacity so a tryEmit from a non-suspending write path never
    // drops the signal even with no active collector at that instant.
    private val _writes = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Emits the mirror table name affected by a local optimistic write. */
    val writes: SharedFlow<String> = _writes

    fun signal(table: String) {
        _writes.tryEmit(table)
    }
}
