package com.gte619n.healthfitness.shared.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorRepositorySupportTest {

    private class NoopEngine : SyncEngine {
        override suspend fun pull() = SyncEngine.SyncResult(0, 0, 0, false)
        override suspend fun drainOutbox() = SyncEngine.SyncResult(0, 0, 0, false)
        override fun firstSyncComplete(): Flow<Boolean> = flowOf(false)
    }

    private fun support(): Triple<MirrorRepositorySupport, SqlDelightOutboxStore, SqlDelightMirrorStore> {
        val db = MirrorDatabaseFactory().open()
        val mirror = SqlDelightMirrorStore(db, NoopPayloadCipher)
        val outbox = SqlDelightOutboxStore(db, NoopPayloadCipher)
        val s = MirrorRepositorySupport(
            mirror, outbox, NoopEngine(),
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        )
        return Triple(s, outbox, mirror)
    }

    @Test
    fun createLocal_shows_in_observe_and_enqueues_outbox() = runTest {
        val (s, outbox, _) = support()
        s.createLocal("medications", "m1", """{"id":"m1","dose":5}""")

        val rows = s.observe("medications").first()
        assertEquals(1, rows.size)
        assertEquals("m1", rows[0].id)
        assertTrue(rows[0].dirty)
        assertEquals("""{"id":"m1","dose":5}""", rows[0].payloadJson)

        val pending = outbox.pending()
        assertEquals(1, pending.size)
        assertEquals(OutboxOp.Operation.CREATE, pending[0].operation)
        assertEquals("m1", pending[0].entityId)
    }

    @Test
    fun deleteLocal_tombstones_and_enqueues_delete() = runTest {
        val (s, outbox, _) = support()
        s.createLocal("medications", "m1", """{"id":"m1"}""")
        s.deleteLocal("medications", "m1")

        assertTrue(s.observe("medications").first().isEmpty())
        // A CREATE then a DELETE are both queued (the drain/reducer collapses them server-side).
        assertEquals(2, outbox.pending().size)
        assertTrue(outbox.pending().any { it.operation == OutboxOp.Operation.DELETE })
    }
}
