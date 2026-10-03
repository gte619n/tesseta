package com.gte619n.healthfitness.shared.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineImplTest {

    private fun db() = MirrorDatabaseFactory().open()

    private fun change(id: String, lastUpdate: String) = ChangeDto(
        collection = "medications",
        id = id,
        status = "ACTIVE",
        lastUpdate = lastUpdate,
        doc = Json.parseToJsonElement("""{"id":"$id"}"""),
    )

    /** A scripted SyncApi: pull serves canned pages in order; push replays a fixed verdict per op id. */
    private class FakeApi(
        private val pages: MutableList<SyncResponse>,
        private val verdicts: Map<String, PushResult> = emptyMap(),
    ) : SyncApi {
        val pushed = mutableListOf<String>()
        override suspend fun pull(since: String?, limit: Int, schemaVersion: Int, recentSince: String?): SyncResponse =
            pages.removeAt(0)
        override suspend fun push(op: OutboxOp): PushResult {
            pushed += op.id
            return verdicts[op.id] ?: PushResult(ok = true, serverLastUpdate = null, retryable = false)
        }
    }

    @Test
    fun pull_pages_to_convergence_applies_changes_and_advances_cursor() = runTest {
        val db = db()
        val mirror = SqlDelightMirrorStore(db, NoopPayloadCipher)
        val outbox = SqlDelightOutboxStore(db, NoopPayloadCipher)
        val api = FakeApi(
            mutableListOf(
                SyncResponse(1, "2026-01-01T00:00:00Z", listOf(change("A", "2026-01-01T00:00:00Z"), change("B", "2026-01-02T00:00:00Z")), "c1", hasMore = true),
                SyncResponse(1, "2026-01-03T00:00:00Z", listOf(change("C", "2026-01-03T00:00:00Z")), "c2", hasMore = false),
            ),
        )
        val engine = SyncEngineImpl(api, mirror, outbox)

        val result = engine.pull()

        assertEquals(3, result.applied)
        assertEquals("2026-01-01T00:00:00Z", mirror.localLastUpdate("medications", "A"))
        assertEquals("2026-01-03T00:00:00Z", mirror.localLastUpdate("medications", "C"))
        assertEquals("c2", mirror.cursorFor("__global__").cursor)
    }

    @Test
    fun drain_pushes_success_drops_terminal_keeps_retryable() = runTest {
        val db = db()
        val mirror = SqlDelightMirrorStore(db, NoopPayloadCipher)
        val outbox = SqlDelightOutboxStore(db, NoopPayloadCipher)
        fun op(id: String) = OutboxOp(
            id = id, collection = "medications", entityId = id, docJson = """{"x":1}""",
            operation = OutboxOp.Operation.CREATE, idempotencyKey = id, enqueuedAt = "2000-01-01T00:00:00Z",
        )
        outbox.enqueue(op("ok1"))
        outbox.enqueue(op("terminal"))
        outbox.enqueue(op("retry"))

        val api = FakeApi(
            mutableListOf(),
            verdicts = mapOf(
                "ok1" to PushResult(ok = true, serverLastUpdate = null, retryable = false),
                "terminal" to PushResult(ok = false, serverLastUpdate = null, retryable = false, error = "HTTP 400"),
                "retry" to PushResult(ok = false, serverLastUpdate = null, retryable = true, error = "HTTP 503"),
            ),
        )
        val engine = SyncEngineImpl(api, mirror, outbox)

        val result = engine.drainOutbox()

        assertEquals(2, result.pushed)  // ok1 cleared + terminal self-healed (dropped)
        assertEquals(1, result.failed)  // retry backed off
        val remaining = outbox.pending().map { it.id }
        assertEquals(listOf("retry"), remaining)
    }

    @Test
    fun pull_wipes_once_on_schema_bump() = runTest {
        val db = db()
        val mirror = SqlDelightMirrorStore(db, NoopPayloadCipher)
        val outbox = SqlDelightOutboxStore(db, NoopPayloadCipher)
        // Seed a stale row + cursor, then the server reports a newer schema.
        mirror.applyServerChange(change("old", "2025-01-01T00:00:00Z"))
        mirror.saveCursor(CursorState("__global__", "stale", "2025-01-01T00:00:00Z"))
        val api = FakeApi(
            mutableListOf(
                SyncResponse(99, "2026-01-01T00:00:00Z", emptyList(), null, hasMore = false),
                SyncResponse(1, "2026-01-01T00:00:00Z", listOf(change("fresh", "2026-01-01T00:00:00Z")), "c1", hasMore = false),
            ),
        )
        val engine = SyncEngineImpl(api, mirror, outbox)

        val result = engine.pull()

        assertTrue(result.schemaBumped)
        assertEquals(null, mirror.localLastUpdate("medications", "old"))  // wiped
        assertEquals("2026-01-01T00:00:00Z", mirror.localLastUpdate("medications", "fresh"))
        assertEquals("c1", mirror.cursorFor("__global__").cursor)
    }
}
