package com.gte619n.healthfitness.shared.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * IMPL-IOS-01 Phase D — the shared read-through / write-through rail every
 * mirror-backed repository builds on (KMP port of Android's MirrorRepositorySupport).
 *
 * READ: [observe] streams the decrypted ACTIVE rows for a collection from the
 * on-device mirror (populated by the sync engine's delta pull). WRITE:
 * [createLocal]/[updateLocal]/[deleteLocal] apply an optimistic, dirty mirror row
 * AND enqueue the matching outbox op, then kick a drain — so a local edit shows
 * instantly, survives offline / process death, and replays with its idempotency key
 * when connectivity returns. The provisional `lastUpdate` is a far-future sentinel so
 * the local edit wins LWW until the server's authoritative row supersedes it.
 */
class MirrorRepositorySupport(
    private val mirror: SqlDelightMirrorStore,
    private val outbox: OutboxStore,
    private val engine: SyncEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val now: () -> String = { Clock.System.now().toString() },
) {

    /** Reactive decrypted ACTIVE rows for [collection] (newest first). */
    fun observe(collection: String): Flow<List<SqlDelightMirrorStore.Record>> =
        mirror.observeActiveRecords(collection)

    /** One decrypted row, or null. */
    fun record(collection: String, id: String): SqlDelightMirrorStore.Record? =
        mirror.record(collection, id)

    /** Optimistic create: dirty mirror row + CREATE outbox op + drain. */
    suspend fun createLocal(collection: String, id: String, payloadJson: String) =
        write(collection, id, payloadJson, OutboxOp.Operation.CREATE)

    /** Optimistic update: dirty mirror row + UPDATE outbox op + drain. */
    suspend fun updateLocal(collection: String, id: String, payloadJson: String) =
        write(collection, id, payloadJson, OutboxOp.Operation.UPDATE)

    /** Optimistic delete: dirty tombstone + DELETE outbox op + drain. */
    suspend fun deleteLocal(collection: String, id: String) {
        mirror.archiveLocal(collection, id, provisionalStamp(id))
        outbox.enqueue(op(collection, id, null, OutboxOp.Operation.DELETE))
        kickDrain()
    }

    private suspend fun write(collection: String, id: String, payloadJson: String, operation: OutboxOp.Operation) {
        mirror.writeLocal(collection, id, payloadJson, provisionalStamp(id))
        outbox.enqueue(op(collection, id, payloadJson, operation))
        kickDrain()
    }

    private fun op(collection: String, id: String, payloadJson: String?, operation: OutboxOp.Operation): OutboxOp {
        val mutationId = mint()
        return OutboxOp(
            id = mutationId,
            collection = collection,
            entityId = id,
            docJson = payloadJson,
            operation = operation,
            idempotencyKey = mutationId, // KtorSyncApi derives the deterministic key per table
            enqueuedAt = now(),
        )
    }

    private fun kickDrain() {
        scope.launch { runCatching { engine.drainOutbox() } }
    }

    /** Far-future provisional stamp so a dirty local edit wins LWW until the server
     *  row supersedes it (parity with Android's "9999-local-…" stamp). */
    private fun provisionalStamp(id: String): String = "9999-local-$id-${now()}"

    private fun mint(): String = "ios-" + Random.nextLong().toString(16).removePrefix("-")
}
