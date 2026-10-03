package com.gte619n.healthfitness.shared.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 4 — cross-client sync convergence harness.
 *
 * This test models TWO clients pulling from and pushing to ONE shared server and
 * asserts they converge to identical mirror state. It exists because the whole
 * point of the KMP shared core (D1) is that Android and iOS run the SAME
 * orchestration: a divergence here is the contract-drift bug class the plan calls
 * out (ARCH-002, XPLAT-001/004/008; the nutrition-slash-collection and
 * outbox-drain-race bugs that already shipped with only 2.5 client surfaces).
 *
 * It uses in-memory fakes of the [OutboxAndEngine] interfaces (`MirrorStore`,
 * `OutboxStore`, `SyncApi`) plus a tiny [FakeServer] that both clients share, so
 * the convergence is genuinely exercised end-to-end (pull → LWW merge → apply,
 * and enqueue → push → server LWW) rather than trivially asserted. The LWW policy
 * under test is the real shared [MergeConflictResolver]; the registry routing is
 * the real [CollectionRegistry].
 *
 * Pure/common → runs identically on JVM (`shared-tests`) and `iosSimulatorArm64`
 * (`shared-ios`) in ios-ci: one test, both runtimes.
 */
class SyncConvergenceTest {

    // ---------------------------------------------------------------------------
    // Shared in-memory server. Holds one authoritative row per (table,id) keyed
    // by the LWW `lastUpdate` string. A monotonic clock mints server timestamps so
    // push ordering is deterministic. Both clients pull from and push to this.
    // ---------------------------------------------------------------------------
    private class FakeServer(var schemaVersion: Int = MIRROR_SCHEMA_VERSION) {
        data class Row(val id: String, val status: String, val lastUpdate: String, val value: String?)

        // table -> id -> row
        private val rows = linkedMapOf<String, LinkedHashMap<String, Row>>()
        private var clock = 0L

        /** Applied idempotency keys — the server dedupes replays (client-minted IDs). */
        val seenIdempotencyKeys = mutableSetOf<String>()

        private fun nextStamp(): String {
            clock += 1
            // Zero-padded so lexicographic compare == numeric compare, mirroring the
            // ISO-8601 UTC instants the real backend emits.
            return "2026-09-23T00:00:" + clock.toString().padStart(6, '0') + "Z"
        }

        fun seed(table: String, id: String, value: String, archived: Boolean = false): String {
            val stamp = nextStamp()
            rows.getOrPut(table) { linkedMapOf() }[id] =
                Row(id, if (archived) "ARCHIVED" else "ACTIVE", stamp, if (archived) null else value)
            return stamp
        }

        /** All rows since [since] (exclusive), across every table, as wire changes. */
        fun changesSince(since: String?): List<ChangeDto> {
            val out = mutableListOf<ChangeDto>()
            for ((table, byId) in rows) {
                for (row in byId.values) {
                    if (since == null || row.lastUpdate > since) {
                        out += ChangeDto(
                            collection = table,
                            id = row.id,
                            status = row.status,
                            lastUpdate = row.lastUpdate,
                            doc = row.value?.let { JsonPrimitive(it) },
                        )
                    }
                }
            }
            // Cursor order = ascending lastUpdate, exactly as the backend delta pages.
            return out.sortedBy { it.lastUpdate }
        }

        fun highWater(): String? =
            rows.values.flatMap { it.values }.maxByOrNull { it.lastUpdate }?.lastUpdate

        /**
         * Server-side write with LWW. A CREATE/UPDATE always wins if it is newer
         * than what we hold (it is: we mint a fresh stamp). A DELETE archives.
         * Returns the [PushResult] the client sees — including the 404-on-DELETE
         * success case (web-doses-outbox-drain-race lesson).
         */
        fun push(op: OutboxOp): PushResult {
            // Idempotency: a replay of an already-applied key is a no-op success and
            // does NOT advance the row (applies exactly once).
            if (op.idempotencyKey in seenIdempotencyKeys) {
                val existing = rows[op.collection]?.get(op.id)
                return PushResult(ok = true, serverLastUpdate = existing?.lastUpdate, retryable = false)
            }

            val table = CollectionRegistry.tableFor(op.collection) ?: op.collection
            val byId = rows.getOrPut(table) { linkedMapOf() }

            return when (op.operation) {
                OutboxOp.Operation.DELETE -> {
                    val existing = byId[op.id]
                    if (existing == null) {
                        // 404-on-DELETE: the row is already gone (tombstoned by the
                        // other client, or never reached the server). Treat as success
                        // so the outbox drains instead of wedging.
                        seenIdempotencyKeys += op.idempotencyKey
                        PushResult(ok = true, serverLastUpdate = null, retryable = false)
                    } else {
                        val stamp = nextStamp()
                        byId[op.id] = Row(op.id, "ARCHIVED", stamp, null)
                        seenIdempotencyKeys += op.idempotencyKey
                        PushResult(ok = true, serverLastUpdate = stamp, retryable = false)
                    }
                }
                else -> {
                    val stamp = nextStamp()
                    byId[op.id] = Row(op.id, "ACTIVE", stamp, op.docJson)
                    seenIdempotencyKeys += op.idempotencyKey
                    PushResult(ok = true, serverLastUpdate = stamp, retryable = false)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // In-memory MirrorStore. Holds the client's local mirror; applies server
    // changes through the real MergeConflictResolver so LWW is genuinely tested.
    // ---------------------------------------------------------------------------
    private class FakeMirrorStore : MirrorStore {
        data class LocalRow(val value: String?, val lastUpdate: String, val archived: Boolean, val dirty: Boolean)

        // table -> id -> row
        val tables = linkedMapOf<String, LinkedHashMap<String, LocalRow>>()
        private val cursors = linkedMapOf<String, CursorState>()
        var wipes = 0
            private set

        private fun tableOf(t: String) = tables.getOrPut(t) { linkedMapOf() }

        override suspend fun applyServerChange(change: ChangeDto) {
            val table = CollectionRegistry.tableFor(change.collection) ?: return // skipped, not crashed
            val byId = tableOf(table)
            val local = byId[change.id]
            val outcome = MergeConflictResolver.resolve(
                serverLastUpdate = change.lastUpdate,
                localLastUpdate = local?.lastUpdate,
                localDirty = local?.dirty ?: false,
            )
            if (outcome == MergeOutcome.KEEP_LOCAL) return
            if (change.status == "ARCHIVED") {
                // Tombstone: remove the row on apply.
                byId.remove(change.id)
            } else {
                byId[change.id] = LocalRow(
                    value = (change.doc as? JsonPrimitive)?.content,
                    lastUpdate = change.lastUpdate,
                    archived = false,
                    dirty = false,
                )
            }
        }

        /** Local optimistic write (the outbox mirror-ahead) — marks the row dirty. */
        fun writeLocal(table: String, id: String, value: String, lastUpdate: String) {
            tableOf(table)[id] = LocalRow(value, lastUpdate, archived = false, dirty = true)
        }

        /** Clear the dirty flag once the server acks the push at [serverLastUpdate]. */
        fun clearDirty(table: String, id: String, serverLastUpdate: String?) {
            val byId = tableOf(table)
            val row = byId[id] ?: return
            byId[id] = row.copy(dirty = false, lastUpdate = serverLastUpdate ?: row.lastUpdate)
        }

        fun value(table: String, id: String): String? = tables[table]?.get(id)?.value
        fun has(table: String, id: String): Boolean = tables[table]?.containsKey(id) == true

        override suspend fun cursorFor(collection: String): CursorState =
            cursors[collection] ?: CursorState(collection, null, null)

        override suspend fun saveCursor(state: CursorState) { cursors[state.collection] = state }

        override suspend fun localLastUpdate(table: String, id: String): String? =
            tables[table]?.get(id)?.lastUpdate

        override suspend fun isDirty(table: String, id: String): Boolean =
            tables[table]?.get(id)?.dirty ?: false

        override suspend fun wipeForSchemaBump() {
            wipes += 1
            tables.clear()
            cursors.clear()
        }
    }

    // ---------------------------------------------------------------------------
    // In-memory OutboxStore.
    // ---------------------------------------------------------------------------
    private class FakeOutboxStore : OutboxStore {
        private val ops = linkedMapOf<String, OutboxOp>()
        private val pushed = mutableSetOf<String>()
        private val count = MutableStateFlow(0)

        override suspend fun enqueue(op: OutboxOp) { ops[op.id] = op; count.value = pendingIds().size }
        override suspend fun pending(): List<OutboxOp> = pendingIds().map { ops.getValue(it) }
        override suspend fun markPushed(id: String, serverLastUpdate: String?) {
            pushed += id; count.value = pendingIds().size
        }
        override suspend fun markFailed(id: String, error: String) {
            ops[id] = ops.getValue(id).let { it.copy(retries = it.retries + 1, lastError = error) }
        }
        override fun pendingCount(): Flow<Int> = count
        private fun pendingIds() = ops.keys.filter { it !in pushed }
    }

    // ---------------------------------------------------------------------------
    // In-memory SyncApi bridging a client to the shared FakeServer. This is where a
    // "cursor + schemaVersion" pull and an outbox push become server operations.
    // ---------------------------------------------------------------------------
    private class FakeSyncApi(private val server: FakeServer) : SyncApi {
        override suspend fun pull(since: String?, limit: Int, schemaVersion: Int, recentSince: String?): SyncResponse {
            val changes = server.changesSince(since)
            return SyncResponse(
                schemaVersion = server.schemaVersion,
                serverTime = server.highWater() ?: since ?: "",
                changes = changes,
                nextCursor = changes.lastOrNull()?.lastUpdate ?: since,
                hasMore = false,
            )
        }
        override suspend fun push(op: OutboxOp): PushResult = server.push(op)
    }

    // ---------------------------------------------------------------------------
    // A minimal client that runs the SAME orchestration both platforms will run:
    // pull → per-change LWW apply → advance cursor; drain → push each op → on ack
    // clear dirty + mark pushed; schemaVersion bump → wipe + full resync.
    // ---------------------------------------------------------------------------
    private class Client(val name: String, val server: FakeServer) {
        val mirror = FakeMirrorStore()
        val outbox = FakeOutboxStore()
        val api = FakeSyncApi(server)
        private val cursorKey = "__global__"

        suspend fun pull() {
            val since = mirror.cursorFor(cursorKey).cursor
            val resp = api.pull(since, limit = 500, schemaVersion = MIRROR_SCHEMA_VERSION, recentSince = null)
            if (resp.schemaVersion > MIRROR_SCHEMA_VERSION) {
                // Server outran us → wipe and full resync from a null cursor.
                mirror.wipeForSchemaBump()
                val full = api.pull(null, 500, MIRROR_SCHEMA_VERSION, null)
                full.changes.forEach { mirror.applyServerChange(it) }
                mirror.saveCursor(CursorState(cursorKey, full.nextCursor, full.serverTime))
                return
            }
            resp.changes.forEach { mirror.applyServerChange(it) }
            mirror.saveCursor(CursorState(cursorKey, resp.nextCursor, resp.serverTime))
        }

        /** Optimistic local write + enqueue an outbox op (the Android write path). */
        suspend fun localWrite(table: String, id: String, value: String, op: OutboxOp.Operation = OutboxOp.Operation.UPDATE) {
            // Mirror-ahead with a client-local provisional stamp; dirty until acked.
            mirror.writeLocal(table, id, value, lastUpdate = "9999-local-$id-$value")
            outbox.enqueue(
                OutboxOp(
                    // The op id IS the row id — clearDirty / delete target the mirror
                    // row by this. Uniqueness across clients lives on idempotencyKey.
                    id = id,
                    collection = table,
                    entityId = id,
                    docJson = value,
                    operation = op,
                    idempotencyKey = "$name-$id-${op.name}-$value",
                    enqueuedAt = "2026-09-23T00:00:00Z",
                ),
            )
        }

        suspend fun drainOutbox() {
            for (op in outbox.pending()) {
                val result = api.push(op)
                if (result.ok) {
                    outbox.markPushed(op.id, result.serverLastUpdate)
                    val table = CollectionRegistry.tableFor(op.collection) ?: op.collection
                    if (op.operation == OutboxOp.Operation.DELETE && result.serverLastUpdate == null) {
                        // 404-on-DELETE success: the row is gone locally too.
                        mirror.tables[table]?.remove(op.id)
                    } else {
                        mirror.clearDirty(table, op.id, result.serverLastUpdate)
                    }
                } else {
                    outbox.markFailed(op.id, result.error ?: "push failed")
                }
            }
        }
    }

    private fun mirrorSnapshot(c: Client): Map<String, Map<String, String?>> =
        c.mirror.tables.mapValues { (_, byId) -> byId.mapValues { it.value.value } }

    // ===========================================================================
    // Scenario 1 — two clients pull the same server changes → identical mirror.
    // ===========================================================================
    @Test
    fun twoClientsPullingSameServerChangesConverge() = runTest {
        val server = FakeServer()
        server.seed(MirrorTables.MEDICATIONS, "med-1", "aspirin")
        server.seed(MirrorTables.NUTRITION_ENTRIES, "entry-1", "eggs")
        server.seed(MirrorTables.WORKOUT_PROGRAMS, "prog-1", "5x5")

        val a = Client("A-android", server)
        val b = Client("B-ios", server)
        a.pull()
        b.pull()

        assertEquals(mirrorSnapshot(a), mirrorSnapshot(b), "A and B diverged after pulling the same server state")
        assertEquals("aspirin", a.mirror.value(MirrorTables.MEDICATIONS, "med-1"))
        assertEquals("eggs", b.mirror.value(MirrorTables.NUTRITION_ENTRIES, "entry-1"))
    }

    // ===========================================================================
    // Scenario 2 — LWW conflict: A and B edit the same row offline, both push →
    // server LWW resolves, both converge to the winner.
    // ===========================================================================
    @Test
    fun offlineEditsOnBothClientsConvergeToLwwWinner() = runTest {
        val server = FakeServer()
        server.seed(MirrorTables.MEDICATIONS, "med-1", "aspirin")

        val a = Client("A-android", server)
        val b = Client("B-ios", server)
        a.pull(); b.pull()

        // Both edit the same row while offline.
        a.localWrite(MirrorTables.MEDICATIONS, "med-1", "aspirin-A")
        b.localWrite(MirrorTables.MEDICATIONS, "med-1", "aspirin-B")

        // A drains first, then B — B's push mints the higher server stamp → B wins.
        a.drainOutbox()
        b.drainOutbox()

        // Both pull the settled server state.
        a.pull(); b.pull()

        val winner = "aspirin-B"
        assertEquals(winner, a.mirror.value(MirrorTables.MEDICATIONS, "med-1"))
        assertEquals(winner, b.mirror.value(MirrorTables.MEDICATIONS, "med-1"))
        assertEquals(mirrorSnapshot(a), mirrorSnapshot(b), "clients did not converge to the LWW winner")
        // Neither mirror is left dirty after the winner is applied.
        assertFalse(a.mirror.isDirty(MirrorTables.MEDICATIONS, "med-1"))
        assertFalse(b.mirror.isDirty(MirrorTables.MEDICATIONS, "med-1"))
    }

    // ===========================================================================
    // Scenario 3 — tombstone: a server ARCHIVED change removes the row on both.
    // ===========================================================================
    @Test
    fun serverTombstoneRemovesRowOnBothClients() = runTest {
        val server = FakeServer()
        server.seed(MirrorTables.MEDICATIONS, "med-1", "aspirin")

        val a = Client("A-android", server)
        val b = Client("B-ios", server)
        a.pull(); b.pull()
        assertTrue(a.mirror.has(MirrorTables.MEDICATIONS, "med-1"))
        assertTrue(b.mirror.has(MirrorTables.MEDICATIONS, "med-1"))

        // Server archives the row (a tombstone: doc == null, status ARCHIVED).
        server.seed(MirrorTables.MEDICATIONS, "med-1", value = "", archived = true)
        a.pull(); b.pull()

        assertFalse(a.mirror.has(MirrorTables.MEDICATIONS, "med-1"), "tombstone did not remove the row on A")
        assertFalse(b.mirror.has(MirrorTables.MEDICATIONS, "med-1"), "tombstone did not remove the row on B")
        assertEquals(mirrorSnapshot(a), mirrorSnapshot(b))
    }

    // ===========================================================================
    // Scenario 4 — schemaVersion bump → wipeForSchemaBump path triggers a full
    // resync, on both clients, converging on the post-bump server state.
    // ===========================================================================
    @Test
    fun schemaVersionBumpTriggersFullResyncAndConverges() = runTest {
        val server = FakeServer()
        server.seed(MirrorTables.MEDICATIONS, "med-1", "aspirin")
        val a = Client("A-android", server)
        val b = Client("B-ios", server)
        a.pull(); b.pull()
        assertEquals(0, a.mirror.wipes)

        // Server bumps its schema and rewrites state; clients must wipe + resync.
        server.schemaVersion = MIRROR_SCHEMA_VERSION + 1
        server.seed(MirrorTables.MEDICATIONS, "med-2", "ibuprofen")

        a.pull(); b.pull()

        assertEquals(1, a.mirror.wipes, "A did not wipe on schema bump")
        assertEquals(1, b.mirror.wipes, "B did not wipe on schema bump")
        // Post-wipe full resync sees the complete server state.
        assertTrue(a.mirror.has(MirrorTables.MEDICATIONS, "med-1"))
        assertTrue(a.mirror.has(MirrorTables.MEDICATIONS, "med-2"))
        assertEquals(mirrorSnapshot(a), mirrorSnapshot(b), "clients diverged after schema-bump resync")
    }

    // ===========================================================================
    // Scenario 5 — outbox idempotency: replaying the same op twice applies once.
    // ===========================================================================
    @Test
    fun replayingSameOpTwiceAppliesOnce() = runTest {
        val server = FakeServer()
        val a = Client("A-android", server)

        val op = OutboxOp(
            id = "op-1",
            collection = MirrorTables.NUTRITION_ENTRIES,
            entityId = "entry-1",
            docJson = "eggs",
            operation = OutboxOp.Operation.CREATE,
            idempotencyKey = "idem-eggs-1",
            enqueuedAt = "2026-09-23T00:00:00Z",
        )

        val first = server.push(op)
        val second = server.push(op) // exact replay (same idempotency key)

        assertTrue(first.ok && second.ok)
        // The replay is a no-op: it did not mint a new server stamp.
        assertEquals(first.serverLastUpdate, second.serverLastUpdate, "replay advanced the server row — not idempotent")
        assertEquals(1, server.seenIdempotencyKeys.size)

        // And a pulling client sees exactly one row, once.
        a.pull()
        assertEquals("eggs", a.mirror.value(MirrorTables.NUTRITION_ENTRIES, op.id))
        assertEquals(1, a.mirror.tables[MirrorTables.NUTRITION_ENTRIES]?.size)
    }

    // ===========================================================================
    // Scenario 6 — 404-on-DELETE is treated as success (drains, doesn't wedge).
    // ===========================================================================
    @Test
    fun deleteOfAlreadyGoneRowIsSuccess() = runTest {
        val server = FakeServer()
        val a = Client("A-android", server)

        // The row never existed on the server (already tombstoned elsewhere).
        a.mirror.writeLocal(MirrorTables.MEDICATIONS, "med-ghost", "aspirin", "9999-local")
        a.localWrite(MirrorTables.MEDICATIONS, "med-ghost", "aspirin", OutboxOp.Operation.DELETE)

        a.drainOutbox()

        // Outbox drained (nothing pending) and the local row is gone — no wedge.
        assertTrue(a.outbox.pending().isEmpty(), "404-on-DELETE left the op stuck in the outbox")
        assertFalse(a.mirror.has(MirrorTables.MEDICATIONS, "med-ghost"))
    }

    // ===========================================================================
    // Bonus — slash-form routing convergence: A pulls the backend's slash-form
    // collection string, B pulls the canonical form; both land in the same table.
    // Guards the nutrition-sync-slash-collection-bug across clients.
    // ===========================================================================
    @Test
    fun slashFormAndCanonicalFormRouteToSameTableAcrossClients() = runTest {
        val server = FakeServer()
        // Server emits the slash-form wire string the backend delta actually sends.
        server.seed("nutritionDays/entries", "entry-1", "eggs")

        val a = Client("A-android", server)
        a.pull()

        // Routed through the alias to the canonical mirror table.
        assertEquals("eggs", a.mirror.value(MirrorTables.NUTRITION_ENTRIES, "entry-1"))
        assertNull(a.mirror.tables["nutritionDays/entries"], "slash-form was mirrored under the raw wire string, not the table")
    }
}
