package com.gte619n.healthfitness.data.sync

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorRepositorySupportTest {

    /** Tracks transaction entry + flags any upsert that lands outside one. */
    private class TxTrackingMirrorOps : FakeMirrorOps() {
        var txCount = 0
        var upsertsOutsideTx = 0
        private var depth = 0

        override suspend fun runInTransaction(block: suspend () -> Unit) {
            txCount++
            depth++
            try {
                block()
            } finally {
                depth--
            }
        }

        override suspend fun upsert(table: String, row: MirrorRowData) {
            if (depth == 0) upsertsOutsideTx++
            super.upsert(table, row)
        }
    }

    private fun support(mirror: MirrorOps) = MirrorRepositorySupport(
        mirror = mirror,
        outbox = mockk(relaxed = true),
        killSwitch = KillSwitchGate { false },
        drainTrigger = DrainTrigger { },
    )

    @Test
    fun `refreshInto writes the whole fill in one transaction`() = runBlocking {
        // A multi-row fill must emit to Room observers once, not once per row —
        // otherwise charts backed by the table redraw/rescale as the fill streams
        // in. Proxy that here by asserting a single transaction wraps every upsert.
        val mirror = TxTrackingMirrorOps()
        val rows = (1..5).map {
            MirrorRepositorySupport.RefreshRow(id = "id$it", payloadJson = "{}", lastUpdate = it.toLong())
        }

        support(mirror).refreshInto("bodyComposition", rows)

        assertEquals(1, mirror.txCount)
        assertEquals(0, mirror.upsertsOutsideTx)
        assertEquals(5, mirror.rows.size)
    }
}
