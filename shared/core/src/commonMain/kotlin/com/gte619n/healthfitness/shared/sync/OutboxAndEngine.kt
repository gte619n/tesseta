package com.gte619n.healthfitness.shared.sync

import kotlinx.coroutines.flow.Flow

/**
 * IMPL-IOS-01 Phase 1C — the shared engine interfaces. The concrete Room-KMP
 * DAOs (D5) and the Ktor client (D4) are `actual` implementations wired per
 * platform, but the ORCHESTRATION (delta pull loop, cursor advance, outbox
 * drain, idempotency) lives here in common code so Android and iOS behave
 * identically. This is the port target of android/core-data .../data/sync/.
 *
 * NOTE: interfaces + policy only in this file; the concrete SyncEngineImpl and
 * Room storage land in Phase 1C proper (see decision log — authored skeleton).
 */

/** A local write awaiting replay. Mirrors the Android `OutboxEntity`. */
data class OutboxOp(
    /** Mutation id (PK); also the default Idempotency-Key for non-deterministic tables. */
    val id: String,
    val collection: String,
    /** The document id the op targets — drives the replay URL (OutboxEndpointRegistry). */
    val entityId: String,
    /** Request payload (plaintext here; the store encrypts it at rest). Null for DELETE. */
    val docJson: String?,
    val operation: Operation,
    val idempotencyKey: String,
    val enqueuedAt: String,
    val retries: Int = 0,
    val lastError: String? = null,
) {
    enum class Operation { CREATE, UPDATE, DELETE }
}

/** Per-collection cursor state. Mirrors the Android `SyncStateEntity`. */
data class CursorState(val collection: String, val cursor: String?, val lastPulledAt: String?)

interface MirrorStore {
    suspend fun applyServerChange(change: ChangeDto)
    suspend fun cursorFor(collection: String): CursorState
    suspend fun saveCursor(state: CursorState)
    suspend fun localLastUpdate(table: String, id: String): String?
    suspend fun isDirty(table: String, id: String): Boolean
    /** Wipe + full resync trigger when the server's schemaVersion outruns ours. */
    suspend fun wipeForSchemaBump()
}

interface OutboxStore {
    suspend fun enqueue(op: OutboxOp)
    suspend fun pending(): List<OutboxOp>
    suspend fun markPushed(id: String, serverLastUpdate: String?)
    suspend fun markFailed(id: String, error: String)
    fun pendingCount(): Flow<Int>
}

/** The HTTP surface both platforms hit (Ktor engine differs per target). */
interface SyncApi {
    suspend fun pull(since: String?, limit: Int, schemaVersion: Int, recentSince: String?): SyncResponse
    suspend fun push(op: OutboxOp): PushResult
}

/**
 * Result of replaying one outbox op. A 404-on-DELETE is success
 * (web-doses-outbox-drain-race lesson); a 401 triggers a silent refresh + retry
 * (refresh-token-family-burn-logout-bug: tolerate the reuse-grace window).
 */
data class PushResult(val ok: Boolean, val serverLastUpdate: String?, val retryable: Boolean, val error: String? = null)

/**
 * Orchestrates the delta pull loop and the outbox drain. Concrete impl is
 * authored in Phase 1C; this interface is what the shared ViewModels and the
 * platform background schedulers (WorkManager on Android, BGTask on iOS, D8)
 * call into.
 */
interface SyncEngine {
    /** Pull every collection to convergence, paging on [SyncResponse.hasMore]. */
    suspend fun pull(): SyncResult
    /** Drain the outbox, replaying each op with its idempotency key. */
    suspend fun drainOutbox(): SyncResult
    /** True once the first full sync has completed (drives the FirstSyncGate). */
    fun firstSyncComplete(): Flow<Boolean>

    data class SyncResult(val applied: Int, val pushed: Int, val failed: Int, val schemaBumped: Boolean)
}

/** The schema version the client understands; a higher server value forces resync. */
const val MIRROR_SCHEMA_VERSION: Int = 1
