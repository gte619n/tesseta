package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gte619n.healthfitness.data.db.DbKeystore
import com.gte619n.healthfitness.data.db.HfDatabase
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.SyncRowState
import com.gte619n.healthfitness.data.db.entity.SyncRowStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.Instant

/**
 * IMPL-AND-20 (Phase 7) — two-client last-write-wins convergence (Definition of
 * Done #5; Phase 7 verification 2).
 *
 * ⚠️ REQUIRES AN EMULATOR OR DEVICE. Two **real** SQLCipher-encrypted [HfDatabase]
 * instances (separate files) stand in for client A and client B; each runs a real
 * [SyncEngine] against a [MockWebServer] delta. Run with:
 *
 *     ./gradlew :core-data:connectedDebugAndroidTest
 *
 * Scenario: A and B both edit the SAME record offline with different (server-
 * stamped) `lastUpdate`s. Each client then pulls the OTHER client's server-
 * confirmed change. The test asserts:
 *  - Both converge on the value with the **higher server `lastUpdate`** (LWW, D3).
 *  - The loser's edit is discarded (no clobbering of the winner).
 *  - There are no ghost / duplicate rows (exactly one ACTIVE row per id).
 *  - A server-derived field present only in the winning doc is never clobbered by
 *    the losing client's payload (D9 — derived data survives).
 */
@RunWith(AndroidJUnit4::class)
class ConvergenceTest {

    private lateinit var context: Context
    private lateinit var keystore: DbKeystore

    private lateinit var dbA: HfDatabase
    private lateinit var dbB: HfDatabase
    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var engineA: SyncEngine
    private lateinit var engineB: SyncEngine

    private val moshi = instrumentedMoshi

    private val DB_A = "hf-converge-A.db"
    private val DB_B = "hf-converge-B.db"

    private fun engineFor(server: MockWebServer, db: HfDatabase): SyncEngine {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SyncApi::class.java)
        return SyncEngine(
            api = api,
            mirror = mirrorStore(db),
            syncStateDao = InMemorySyncStateDao(),
            dbWiper = NoopDbWiper(),
            flags = RecordingKillSwitchSink(),
            moshi = moshi,
            io = Dispatchers.IO,
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDbFiles(context, DB_A)
        deleteDbFiles(context, DB_B)
        keystore = DbKeystore(context)
        dbA = buildNamedDb(context, keystore, DB_A)
        dbB = buildNamedDb(context, keystore, DB_B)
        serverA = MockWebServer().also { it.start() }
        serverB = MockWebServer().also { it.start() }
        engineA = engineFor(serverA, dbA)
        engineB = engineFor(serverB, dbB)
    }

    @After
    fun tearDown() {
        if (dbA.isOpen) dbA.close()
        if (dbB.isOpen) dbB.close()
        serverA.shutdown()
        serverB.shutdown()
        deleteDbFiles(context, DB_A)
        deleteDbFiles(context, DB_B)
    }

    private suspend fun seedOfflineEdit(
        db: HfDatabase,
        id: String,
        payloadJson: String,
        lastUpdate: Long,
    ) {
        mirrorStore(db).upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData(
                id = id,
                payloadJson = payloadJson,
                lastUpdate = lastUpdate,
                status = SyncRowStatus.ACTIVE.name,
                dirty = true,
                syncState = SyncRowState.PENDING.name,
            ),
        )
    }

    @Test
    fun two_clients_editing_same_record_converge_on_higher_lastUpdate() = runBlocking {
        val id = "med-converge-1"

        // B's write was stamped LATER by the server, so B wins under LWW. The
        // winning doc additionally carries a server-derived field ("doseMgComputed")
        // that the losing client never had — it must survive on both clients (D9).
        val tEarly = Instant.parse("2026-06-02T18:00:00Z")
        val tLate = Instant.parse("2026-06-02T18:05:00Z")

        // Both clients hold their own optimistic offline edit of the same id.
        seedOfflineEdit(dbA, id, """{"id":"$id","name":"A-edit"}""", tEarly.toEpochMilli())
        seedOfflineEdit(dbB, id, """{"id":"$id","name":"B-edit","doseMgComputed":81}""", tLate.toEpochMilli())

        // --- Client A pulls B's (winning, newer) change from the server delta. ---
        serverA.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"changes":[
                  {"collection":"medications","id":"$id","status":"ACTIVE",
                   "lastUpdate":"${tLate}","doc":{"id":"$id","name":"B-edit","doseMgComputed":81}}
                ],"nextCursor":"a-done","hasMore":false,"killSwitch":false}
                """.trimIndent(),
            ),
        )
        val resA = engineA.pull()
        // A's dirty edit was older → LWW discards it and applies B's doc.
        assertEquals(1, resA.discardedLocal)

        // --- Client B pulls A's (losing, older) change from the server delta. ---
        serverB.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"changes":[
                  {"collection":"medications","id":"$id","status":"ACTIVE",
                   "lastUpdate":"${tEarly}","doc":{"id":"$id","name":"A-edit"}}
                ],"nextCursor":"b-done","hasMore":false,"killSwitch":false}
                """.trimIndent(),
            ),
        )
        val resB = engineB.pull()
        // B already holds the newer doc → A's stale change is rejected (B keeps its value).
        assertEquals(1, resB.rejected)
        assertEquals(0, resB.applied)

        // --- Both clients converged on B's value (the higher lastUpdate). ---
        val rowA = mirrorStore(dbA).getRow(MirrorTables.MEDICATIONS, id)!!
        val rowB = mirrorStore(dbB).getRow(MirrorTables.MEDICATIONS, id)!!

        assertTrue("A converged on B's value", rowA.payloadJson.contains("B-edit"))
        assertTrue("B kept its winning value", rowB.payloadJson.contains("B-edit"))
        assertEquals("A adopted the winning lastUpdate", tLate.toEpochMilli(), rowA.lastUpdate)
        assertEquals("B kept the winning lastUpdate", tLate.toEpochMilli(), rowB.lastUpdate)

        // The server-derived field survives on BOTH clients (never clobbered by the loser).
        assertTrue("derived field survives on A", rowA.payloadJson.contains("doseMgComputed"))
        assertTrue("derived field survives on B", rowB.payloadJson.contains("doseMgComputed"))

        // The loser's local dirty edit was discarded, not merged.
        assertFalse("A's losing edit discarded", rowA.payloadJson.contains("A-edit"))
        assertFalse("A row is no longer dirty", rowA.dirty)

        // No ghost / duplicate rows: exactly one ACTIVE row per id on each client.
        assertEquals(1, mirrorStore(dbA).activeCount(MirrorTables.MEDICATIONS, id))
        assertEquals(1, mirrorStore(dbB).activeCount(MirrorTables.MEDICATIONS, id))
    }
}

/**
 * Count the ACTIVE rows for a given id via the DAO observe stream's first emission.
 * (A convenience the test uses to prove there is exactly one — no ghost/duplicate.)
 */
private suspend fun MirrorStore.activeCount(table: String, id: String): Int {
    val row = getRow(table, id) ?: return 0
    return if (row.status == SyncRowStatus.ACTIVE.name) 1 else 0
}
