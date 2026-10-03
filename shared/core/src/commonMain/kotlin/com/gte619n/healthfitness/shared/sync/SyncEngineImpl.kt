package com.gte619n.healthfitness.shared.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IMPL-IOS-01 Phase 1C — the shared sync orchestrator. Port of the Android
 * SyncEngine's pull loop + outbox drain; all policy lives here so both clients
 * converge identically (the [SyncConvergenceTest] harness guards it).
 *
 *  - [pull] pages `GET /api/me/sync` to convergence, applying each change through
 *    the LWW [MirrorStore] and advancing a single global cursor. A server
 *    schemaVersion ahead of ours forces one wipe + full resync; a killSwitch stops
 *    the loop. First convergence flips [firstSyncComplete] (drives the sign-in gate).
 *  - [drainOutbox] replays each pending op: success clears it, a retryable failure
 *    backs it off (store-side), a terminal 4xx self-heals by dropping it (the write
 *    the server will never accept must not wedge the queue forever).
 */
class SyncEngineImpl(
    private val api: SyncApi,
    private val mirror: MirrorStore,
    private val outbox: OutboxStore,
    private val pageLimit: Int = 500,
) : SyncEngine {

    private val _firstSyncComplete = MutableStateFlow(false)
    override fun firstSyncComplete(): Flow<Boolean> = _firstSyncComplete.asStateFlow()

    override suspend fun pull(): SyncEngine.SyncResult {
        var applied = 0
        var schemaBumped = false
        var wiped = false
        var cursor = mirror.cursorFor(GLOBAL).cursor

        while (true) {
            val resp = api.pull(cursor, pageLimit, MIRROR_SCHEMA_VERSION, null)
            if (resp.killSwitch) break

            if (resp.schemaVersion != MIRROR_SCHEMA_VERSION) {
                // The server's data outruns this client. Wipe once and resync from
                // scratch; if it still mismatches after the wipe, stop (needs an app
                // upgrade) rather than loop forever.
                if (wiped) break
                mirror.wipeForSchemaBump()
                mirror.saveCursor(CursorState(GLOBAL, null, null))
                cursor = null
                wiped = true
                schemaBumped = true
                continue
            }

            for (change in resp.changes) {
                mirror.applyServerChange(change)
                applied++
            }
            cursor = resp.nextCursor
            mirror.saveCursor(CursorState(GLOBAL, cursor, resp.serverTime))

            if (!resp.hasMore) break
        }

        _firstSyncComplete.value = true
        return SyncEngine.SyncResult(applied = applied, pushed = 0, failed = 0, schemaBumped = schemaBumped)
    }

    override suspend fun drainOutbox(): SyncEngine.SyncResult {
        var pushed = 0
        var failed = 0
        for (op in outbox.pending()) {
            val result = api.push(op)
            when {
                result.ok -> {
                    outbox.markPushed(op.id, result.serverLastUpdate)
                    pushed++
                }
                result.retryable -> {
                    outbox.markFailed(op.id, result.error ?: "retry")
                    failed++
                }
                else -> {
                    // Terminal 4xx — the server will never accept this write; drop it so
                    // it can't wedge the queue. (Mirror-row self-heal is the repo's job.)
                    outbox.markPushed(op.id, null)
                    pushed++
                }
            }
        }
        return SyncEngine.SyncResult(applied = 0, pushed = pushed, failed = failed, schemaBumped = false)
    }

    private companion object {
        /** The backend delta is a unified stream with ONE cursor; we key it under this. */
        const val GLOBAL = "__global__"
    }
}
