package com.gte619n.healthfitness.shared.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1C storage proof: the SQLDelight-backed mirror + outbox stores round-trip
 * through a real (in-memory) SQLite DB with the LWW policy and payload cipher.
 */
class SqlDelightStoresTest {

    private fun change(id: String, lastUpdate: String, status: String = "ACTIVE") = ChangeDto(
        collection = "medications",
        id = id,
        status = status,
        lastUpdate = lastUpdate,
        doc = if (status == "ARCHIVED") null else Json.parseToJsonElement("""{"id":"$id","dose":5}"""),
    )

    @Test
    fun mirror_applies_server_change_and_honours_LWW() = runTest {
        val db = MirrorDatabaseFactory().open()
        val store = SqlDelightMirrorStore(db, NoopPayloadCipher)

        store.applyServerChange(change("A", "2026-01-01T00:00:00Z"))
        assertEquals("2026-01-01T00:00:00Z", store.localLastUpdate("medications", "A"))
        assertTrue(!store.isDirty("medications", "A"))

        // Older server change must NOT overwrite (KEEP_LOCAL).
        store.applyServerChange(change("A", "2025-01-01T00:00:00Z"))
        assertEquals("2026-01-01T00:00:00Z", store.localLastUpdate("medications", "A"))

        // Newer tombstone applies.
        store.applyServerChange(change("A", "2026-06-01T00:00:00Z", status = "ARCHIVED"))
        assertEquals("2026-06-01T00:00:00Z", store.localLastUpdate("medications", "A"))
    }

    @Test
    fun mirror_cursor_round_trips() = runTest {
        val db = MirrorDatabaseFactory().open()
        val store = SqlDelightMirrorStore(db, NoopPayloadCipher)
        assertNull(store.cursorFor("__global__").cursor)
        store.saveCursor(CursorState("__global__", "cursor-1", "2026-10-02T00:00:00Z"))
        assertEquals("cursor-1", store.cursorFor("__global__").cursor)
    }

    @Test
    fun outbox_enqueue_pending_and_push() = runTest {
        val db = MirrorDatabaseFactory().open()
        val outbox = SqlDelightOutboxStore(db, NoopPayloadCipher)

        outbox.enqueue(
            OutboxOp(
                id = "m1",
                collection = "medications",
                entityId = "e1",
                docJson = """{"dose":5}""",
                operation = OutboxOp.Operation.CREATE,
                idempotencyKey = "m1",
                enqueuedAt = "2000-01-01T00:00:00Z",
            ),
        )
        val pending = outbox.pending()
        assertEquals(1, pending.size)
        assertEquals("e1", pending[0].entityId)
        assertEquals("""{"dose":5}""", pending[0].docJson)

        outbox.markPushed("m1", "2026-01-01T00:00:00Z")
        assertTrue(outbox.pending().isEmpty())
    }
}
