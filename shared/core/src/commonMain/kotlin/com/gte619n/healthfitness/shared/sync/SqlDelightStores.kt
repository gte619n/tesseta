package com.gte619n.healthfitness.shared.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.gte619n.healthfitness.shared.db.MirrorDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * IMPL-IOS-01 Phase 1C — SQLDelight-backed [MirrorStore]. Applies server deltas to
 * the generic mirror table with the shared [MergeConflictResolver] LWW policy, and
 * encrypts the PHI payload via [PayloadCipher] before it touches SQLite. The
 * `collection` the engine passes is resolved to a canonical table key through
 * [CollectionRegistry] (forward-compatible: an unknown collection is skipped).
 */
class SqlDelightMirrorStore(
    db: MirrorDatabase,
    private val cipher: PayloadCipher,
    private val json: Json = Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> String = { Clock.System.now().toString() },
) : MirrorStore {

    private val rows = db.mirrorRowQueries
    private val state = db.syncStateQueries

    // MARK: - Repository-facing read/write rail (Phase D)
    //
    // A decrypted mirror row the repositories consume (the db type + cipher stay
    // inside the store).
    data class Record(val id: String, val payloadJson: String, val lastUpdate: String, val dirty: Boolean)

    /** Reactive ACTIVE rows for a collection, decrypted (newest first). */
    fun observeActiveRecords(collection: String): Flow<List<Record>> =
        rows.observeActive(collection).asFlow().mapToList(dispatcher).map { list ->
            list.map { Record(it.id, cipher.decrypt(it.payloadCipher), it.lastUpdate, it.dirty != 0L) }
        }

    /** One decrypted row, or null. */
    fun record(collection: String, id: String): Record? =
        rows.getById(collection, id).executeAsOneOrNull()
            ?.let { Record(it.id, cipher.decrypt(it.payloadCipher), it.lastUpdate, it.dirty != 0L) }

    /** The newest ACTIVE row of a collection, decrypted — for singleton collections
     *  (e.g. userProfile) whose id the caller doesn't know. */
    fun firstActiveRecord(collection: String): Record? =
        rows.observeActive(collection).executeAsList().firstOrNull()
            ?.let { Record(it.id, cipher.decrypt(it.payloadCipher), it.lastUpdate, it.dirty != 0L) }

    /** Optimistic local upsert (dirty, PENDING) — a local write awaiting push. */
    fun writeLocal(collection: String, id: String, payloadJson: String, lastUpdate: String) {
        rows.upsert(collection, id, cipher.encrypt(payloadJson), lastUpdate, SyncStatus.ACTIVE.name, 1L, "PENDING", now())
    }

    /** Optimistic local tombstone (dirty, PENDING) — a local delete awaiting push. */
    fun archiveLocal(collection: String, id: String, lastUpdate: String) {
        rows.markArchived(lastUpdate, 1L, "PENDING", now(), collection, id)
    }

    /**
     * Hard-delete one mirror row locally, no outbox op (Phase G: clearing a live
     * workout draft after a terminal action — the next delta pull re-populates the
     * server-authoritative scheduled row). Distinct from [archiveLocal], which
     * tombstones a row AND marks it a pending DELETE to push.
     */
    fun deleteRowLocal(collection: String, id: String) {
        rows.deleteRow(collection, id)
    }

    /**
     * Write a clean, SYNCED row from a local best-effort network read (NOT a server
     * delta) — e.g. Phase G's cold-miss calendar fetch that mirrors the schedule so
     * [start] can snapshot it. Clean (dirty = 0) so it is NOT mistaken for a local
     * draft and the next real delta can supersede it by LWW.
     */
    fun applyLocalSynced(collection: String, id: String, payloadJson: String, lastUpdate: String) {
        rows.upsert(collection, id, cipher.encrypt(payloadJson), lastUpdate, SyncStatus.ACTIVE.name, 0L, "SYNCED", now())
    }

    override suspend fun applyServerChange(change: ChangeDto) {
        val table = CollectionRegistry.tableFor(change.collection) ?: return
        val local = rows.getById(table, change.id).executeAsOneOrNull()
        val outcome = MergeConflictResolver.resolve(
            serverLastUpdate = change.lastUpdate,
            localLastUpdate = local?.lastUpdate,
            localDirty = (local?.dirty ?: 0L) != 0L,
        )
        if (outcome == MergeOutcome.KEEP_LOCAL) return

        val tombstone = change.status == SyncStatus.ARCHIVED.name || change.doc == null
        if (tombstone) {
            rows.markArchived(change.lastUpdate, 0L, "SYNCED", now(), table, change.id)
        } else {
            val payload = cipher.encrypt(json.encodeToString(JsonElement.serializer(), change.doc!!))
            rows.upsert(table, change.id, payload, change.lastUpdate, SyncStatus.ACTIVE.name, 0L, "SYNCED", now())
        }
    }

    override suspend fun cursorFor(collection: String): CursorState {
        val row = state.get(collection).executeAsOneOrNull()
        return CursorState(collection, row?.cursor, row?.lastPulledAt)
    }

    override suspend fun saveCursor(state: CursorState) {
        this.state.upsert(state.collection, state.cursor, state.lastPulledAt)
    }

    override suspend fun localLastUpdate(table: String, id: String): String? =
        rows.getById(table, id).executeAsOneOrNull()?.lastUpdate

    override suspend fun isDirty(table: String, id: String): Boolean =
        (rows.getById(table, id).executeAsOneOrNull()?.dirty ?: 0L) != 0L

    /** Wipe the mirror + cursor on a server schema bump; the outbox is preserved so
     *  un-pushed local writes survive the forced resync. */
    override suspend fun wipeForSchemaBump() {
        rows.clearAll()
        state.clearAll()
    }

    /** Non-suspend full wipe (mirror rows + cursor) for the sign-out teardown, which
     *  runs synchronously off the Swift auth path. SQLDelight queries are blocking. */
    fun wipeAllBlocking() {
        rows.clearAll()
        state.clearAll()
    }
}

/**
 * IMPL-IOS-01 Phase 1C — SQLDelight-backed [OutboxStore]. Durable write queue:
 * each op gets a monotonic [seq] and its request payload is encrypted at rest.
 * [markPushed] deletes the replayed op; [markFailed] bumps retries (jittered
 * backoff lands with the drain loop in the engine, Phase C).
 */
class SqlDelightOutboxStore(
    db: MirrorDatabase,
    private val cipher: PayloadCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> String = { Clock.System.now().toString() },
) : OutboxStore {

    private val ops = db.outboxOpQueries

    override suspend fun enqueue(op: OutboxOp) {
        val seq = (ops.maxSeq().executeAsOneOrNull()?.MAX ?: 0L) + 1L
        ops.insert(
            id = op.id,
            collection = op.collection,
            entityId = op.entityId,
            operation = op.operation.name,
            docCipher = op.docJson?.let { cipher.encrypt(it) },
            idempotencyKey = op.idempotencyKey,
            seq = seq,
            retries = op.retries.toLong(),
            enqueuedAt = op.enqueuedAt,
            nextAttemptAt = op.enqueuedAt,
            lastError = op.lastError,
        )
    }

    override suspend fun pending(): List<OutboxOp> =
        ops.listDue(now()).executeAsList().map(::toOp)

    override suspend fun markPushed(id: String, serverLastUpdate: String?) {
        ops.deleteById(id)
    }

    override suspend fun markParked(id: String, error: String?) {
        ops.markParked(error, id)
    }

    override suspend fun parked(): List<OutboxOp> =
        ops.listParked().executeAsList().map(::toOp)

    override fun observeParked(): Flow<List<OutboxOp>> =
        ops.listParked().asFlow().mapToList(dispatcher).map { list -> list.map(::toOp) }

    override suspend fun discard(id: String) {
        ops.deleteById(id)
    }

    private fun toOp(row: com.gte619n.healthfitness.shared.db.OutboxOp): OutboxOp =
        OutboxOp(
            id = row.id,
            collection = row.collection,
            entityId = row.entityId,
            docJson = row.docCipher?.let { cipher.decrypt(it) },
            operation = OutboxOp.Operation.valueOf(row.operation),
            idempotencyKey = row.idempotencyKey,
            enqueuedAt = row.enqueuedAt,
            retries = row.retries.toInt(),
            lastError = row.lastError,
        )

    override suspend fun markFailed(id: String, error: String) {
        val row = ops.selectById(id).executeAsOneOrNull() ?: return
        ops.recordFailure(row.retries + 1L, now(), error, id)
    }

    override fun pendingCount(): Flow<Int> =
        ops.pendingCount().asFlow().mapToOne(dispatcher).map { it.toInt() }

    /** Non-suspend outbox clear for the sign-out teardown (see mirror wipeAllBlocking). */
    fun clearAllBlocking() {
        ops.clearAll()
    }
}
