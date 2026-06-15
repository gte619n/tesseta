package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorRow
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.db.entity.SyncRowState
import com.gte619n.healthfitness.data.db.entity.SyncRowStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — the reusable read-path / write-path engine every
 * in-scope repository delegates to so Room is the UI's source of truth (D8).
 *
 * It bundles the three things every migrated repository needs:
 *
 *  1. **Reads are Room Flows.** [observe] maps a DAO `observeActive()` Flow of
 *     mirror rows (the `payloadJson` blobs) through a domain decoder. When the
 *     remote **kill-switch** is latched (D13) it instead serves a one-shot
 *     live-network fetch so a client forced back to live mode still renders.
 *
 *  2. **Optimistic local writes + outbox (D7).** [createLocal]/[updateLocal]/
 *     [deleteLocal] write the mirror row immediately (`dirty=true`,
 *     `syncState=PENDING`, client-minted UUID on create), enqueue the matching
 *     outbox mutation, and kick a drain — so an offline edit appears instantly
 *     and is marked PENDING, never blocking the UI on the network.
 *
 *  3. **Targeted refresh.** [refreshInto] fetches the domain objects over the
 *     network and upserts them into the mirror as SYNCED rows — the repository's
 *     `refresh()` fill path that complements [SyncEngine.pull].
 *
 * Persisting the repository's own DTO/domain JSON as `payloadJson` (rather than
 * relying solely on the sanitized delta `doc`) closes the computed-field gap
 * flagged in the spec: server-*computed* fields the delta omits (e.g. a blood
 * reading's `reference` block) survive because the refresh path stores the full
 * GET-endpoint shape the screen consumes.
 */
@Singleton
class MirrorRepositorySupport @Inject constructor(
    private val mirror: MirrorOps,
    private val outbox: OutboxRepository,
    private val killSwitch: KillSwitchGate,
    private val drainTrigger: DrainTrigger,
    // Defaulted so the many JVM tests that construct this directly need no change;
    // Hilt injects the shared singleton in production (same pattern as
    // OutboxRepository's clock seam).
    private val localWriteBus: LocalWriteBus = LocalWriteBus(),
) {

    /** True ⇒ Room is NOT the source of truth; callers must read live network. */
    suspend fun killSwitchOn(): Boolean = killSwitch.isOn()

    /**
     * Observe a mirror table as a domain Flow.
     *
     * @param rows the DAO `observeActive()` Flow (ACTIVE rows, newest first).
     * @param decode payloadJson → domain (return null to drop an undecodable row).
     * @param liveFallback invoked once per collection when the kill-switch is on,
     *        returning the live-network list to serve instead of Room.
     */
    fun <E : MirrorRow, D> observe(
        rows: Flow<List<E>>,
        decode: (String) -> D?,
        liveFallback: suspend () -> List<D>,
    ): Flow<List<D>> = flow {
        if (killSwitch.isOn()) {
            // D13: kill-switch latched ⇒ serve live network, not Room.
            emit(runCatching { liveFallback() }.getOrDefault(emptyList()))
            return@flow
        }
        emitAll(rows.map { list -> list.mapNotNull { decode(it.payloadJson) } })
    }

    /**
     * Like [observe], but the decoder also receives the row's per-row `syncState`
     * (`SYNCED | PENDING | FAILED`) so the domain model can carry it for the D11
     * per-row [com.gte619n.healthfitness.ui.sync.SyncBadge] (#40). Under the
     * kill-switch the live-fallback items have no mirror state, so a null is passed.
     */
    fun <E : MirrorRow, D> observeWithState(
        rows: Flow<List<E>>,
        decode: (json: String, syncState: String?) -> D?,
        liveFallback: suspend () -> List<D>,
    ): Flow<List<D>> = flow {
        if (killSwitch.isOn()) {
            emit(runCatching { liveFallback() }.getOrDefault(emptyList()))
            return@flow
        }
        emitAll(rows.map { list -> list.mapNotNull { decode(it.payloadJson, it.syncState) } })
    }

    /** Optimistic CREATE: write a PENDING mirror row, enqueue, drain. */
    suspend fun createLocal(table: String, id: String, payloadJson: String, lastUpdate: Long) {
        mirror.upsert(table, pendingRow(id, payloadJson, lastUpdate))
        outbox.enqueue(OutboxOp.CREATE, table, id, payloadJson)
        localWriteBus.signal(table)
        drainTrigger.requestDrain()
    }

    /** Optimistic UPDATE: replace the mirror row (PENDING), enqueue, drain. */
    suspend fun updateLocal(table: String, id: String, payloadJson: String, lastUpdate: Long) {
        mirror.upsert(table, pendingRow(id, payloadJson, lastUpdate))
        outbox.enqueue(OutboxOp.UPDATE, table, id, payloadJson)
        localWriteBus.signal(table)
        drainTrigger.requestDrain()
    }

    /**
     * Optimistic UPDATE whose replayed wire body differs from the mirrored read
     * payload. The workout-session completion (ADR-0012) needs this split: the
     * mirror keeps the `ScheduledWorkoutDto` the calendar screens decode, while
     * the outbox carries the IMPL-17 D2 completion request the PUT endpoint
     * expects. Everything else (PENDING row, enqueue, drain kick) matches
     * [updateLocal].
     */
    suspend fun updateLocalWithWire(
        table: String,
        id: String,
        payloadJson: String,
        wirePayloadJson: String,
        lastUpdate: Long,
    ) {
        mirror.upsert(table, pendingRow(id, payloadJson, lastUpdate))
        outbox.enqueue(OutboxOp.UPDATE, table, id, wirePayloadJson)
        localWriteBus.signal(table)
        drainTrigger.requestDrain()
    }

    /** Optimistic DELETE: tombstone the mirror row, enqueue, drain. */
    suspend fun deleteLocal(table: String, id: String, lastUpdate: Long) {
        mirror.markArchived(table, id, lastUpdate)
        outbox.enqueue(OutboxOp.DELETE, table, id, null)
        localWriteBus.signal(table)
        drainTrigger.requestDrain()
    }

    /**
     * Replace the mirror contents for a freshly-fetched network list. Each row is
     * stored SYNCED+clean with the supplied per-item payload + lastUpdate.
     * Does NOT touch rows that are locally dirty (a pending optimistic edit must
     * not be clobbered by a concurrent refresh).
     */
    suspend fun refreshInto(table: String, items: List<RefreshRow>) {
        // Single transaction ⇒ one Room observer emission for the whole fill, so a
        // chart backed by this table renders the final series at once instead of
        // redrawing/rescaling per row as the fill streams in.
        mirror.runInTransaction {
            for (item in items) {
                val existing = mirror.getRow(table, item.id)
                if (existing?.dirty == true) continue
                mirror.upsert(
                    table,
                    MirrorRowData(
                        id = item.id,
                        payloadJson = item.payloadJson,
                        lastUpdate = item.lastUpdate,
                        status = SyncRowStatus.ACTIVE.name,
                        dirty = false,
                        syncState = SyncRowState.SYNCED.name,
                    ),
                )
            }
        }
    }

    /**
     * Local-only reconcile: archive mirror rows that a refresh found the server
     * no longer has (deleted on another device, or an orphaned placeholder the
     * server cleaned up). Unlike [deleteLocal] this does NOT enqueue an outbox
     * DELETE — the server already lacks these rows, so there's nothing to replay;
     * we just stop surfacing them. [refreshInto] only upserts, so without this a
     * server-side deletion would linger locally forever. Locally-dirty rows (an
     * unsynced optimistic create) are left untouched.
     */
    suspend fun pruneLocal(table: String, ids: Collection<String>, lastUpdate: Long) {
        if (ids.isEmpty()) return
        mirror.runInTransaction {
            for (id in ids) {
                val existing = mirror.getRow(table, id) ?: continue
                if (existing.dirty) continue
                mirror.markArchived(table, id, lastUpdate)
            }
        }
    }

    private fun pendingRow(id: String, payloadJson: String, lastUpdate: Long) = MirrorRowData(
        id = id,
        payloadJson = payloadJson,
        lastUpdate = lastUpdate,
        status = SyncRowStatus.ACTIVE.name,
        dirty = true,
        syncState = SyncRowState.PENDING.name,
    )

    /** One refreshed row to mirror: backend id + its serialized payload + cursor. */
    data class RefreshRow(val id: String, val payloadJson: String, val lastUpdate: Long)
}
