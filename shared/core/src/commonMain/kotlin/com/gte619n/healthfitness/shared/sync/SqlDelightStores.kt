package com.gte619n.healthfitness.shared.sync

import app.cash.sqldelight.coroutines.asFlow
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
    private val now: () -> String = { Clock.System.now().toString() },
) : MirrorStore {

    private val rows = db.mirrorRowQueries
    private val state = db.syncStateQueries

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
        ops.listDue(now()).executeAsList().map { row ->
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
        }

    override suspend fun markPushed(id: String, serverLastUpdate: String?) {
        ops.deleteById(id)
    }

    override suspend fun markFailed(id: String, error: String) {
        val row = ops.selectById(id).executeAsOneOrNull() ?: return
        ops.recordFailure(row.retries + 1L, now(), error, id)
    }

    override fun pendingCount(): Flow<Int> =
        ops.pendingCount().asFlow().mapToOne(dispatcher).map { it.toInt() }
}
