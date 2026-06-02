package com.gte619n.healthfitness.data.sync

/**
 * IMPL-AND-20 (Phase 4) — document-level last-write-wins conflict resolution (D3).
 *
 * Pure and testable: no Android / DB / coroutine dependencies. The SyncEngine
 * feeds it the incoming change's server `lastUpdate` plus the local row's state
 * (its `lastUpdate` and whether it carries a pending dirty mutation) and gets
 * back a [Decision] telling it how to apply the change.
 *
 * Rules (keyed on the **server** clock, never the device clock — invariant 3):
 *  - No local row ⇒ always [Decision.Apply] (first time we see this doc).
 *  - `incoming.lastUpdate >= local.lastUpdate` AND the local row is not dirty ⇒
 *    [Decision.Apply] (newer-or-equal server doc wins cleanly).
 *  - `incoming.lastUpdate >= local.lastUpdate` AND the local row IS dirty ⇒
 *    the server doc is newer than what the local edit was based on, so the local
 *    edit **loses**: [Decision.ApplyDiscardingLocal]. The engine applies the
 *    server doc, clears the dirty flag, drops the outbox mutation, and surfaces
 *    the "updated elsewhere" signal (D11) for Phase 6 UX.
 *  - `incoming.lastUpdate < local.lastUpdate` ⇒ [Decision.Reject] (we already
 *    hold a newer doc; the incoming change is stale, e.g. an out-of-order page
 *    or our own just-confirmed write echoing back).
 *
 * Equal timestamps resolve in favour of the incoming server doc (idempotent: the
 * server doc is authoritative and re-applying it is a no-op when not dirty).
 */
object ConflictResolver {

    sealed interface Decision {
        /** Apply the incoming change; no local edit was at risk. */
        data object Apply : Decision

        /**
         * Apply the incoming change, overwriting a local dirty edit that lost
         * LWW. The engine must also clear `dirty`, discard the entity's pending
         * outbox mutation, and emit the "updated elsewhere" signal.
         */
        data object ApplyDiscardingLocal : Decision

        /** Incoming change is stale; keep the local row untouched. */
        data object Reject : Decision
    }

    /** State of the local mirror row the incoming change targets, if any. */
    data class LocalRow(
        val lastUpdate: Long,
        val dirty: Boolean,
    )

    /**
     * @param incomingLastUpdate server epoch millis on the incoming change.
     * @param local the local mirror row, or null if none exists yet.
     */
    fun resolve(incomingLastUpdate: Long, local: LocalRow?): Decision {
        if (local == null) return Decision.Apply
        return when {
            incomingLastUpdate < local.lastUpdate -> Decision.Reject
            local.dirty -> Decision.ApplyDiscardingLocal
            else -> Decision.Apply
        }
    }
}
