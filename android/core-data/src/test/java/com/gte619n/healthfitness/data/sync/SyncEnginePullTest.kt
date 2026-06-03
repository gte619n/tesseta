package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * IMPL-AND-20 (Phase 4) — SyncEngine pull tests driven by a real Retrofit
 * [SyncApi] against MockWebServer, with in-memory fakes for the DB seams
 * ([FakeMirrorOps]/[FakeSyncStateDao]/[FakeDbWiper]/[FakeKillSwitchSink]).
 *
 * This runs on the **pure JVM** — no Room, no SQLCipher, no Robolectric, no
 * device — which is why the engine was written against narrow interfaces.
 */
class SyncEnginePullTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SyncApi
    private lateinit var mirror: FakeMirrorOps
    private lateinit var syncState: FakeSyncStateDao
    private lateinit var wiper: FakeDbWiper
    private lateinit var flags: FakeKillSwitchSink
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(SyncTestMoshi.instance))
            .build()
            .create(SyncApi::class.java)
        mirror = FakeMirrorOps()
        syncState = FakeSyncStateDao()
        wiper = FakeDbWiper()
        flags = FakeKillSwitchSink()
        engine = SyncEngine(
            api = api,
            mirror = mirror,
            syncStateDao = syncState,
            dbWiper = wiper,
            flags = flags,
            moshi = SyncTestMoshi.instance,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `paginated delta with a tombstone applies upsert plus markArchived and persists cursor`() = runTest {
        // Page 1: one ACTIVE medication, hasMore=true.
        server.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"serverTime":"2026-06-02T18:00:00Z",
                 "changes":[
                   {"collection":"medications","id":"med-1","status":"ACTIVE",
                    "lastUpdate":"2026-06-02T18:00:00Z","doc":{"name":"Aspirin"}}
                 ],
                 "nextCursor":"cur-2","hasMore":true,"killSwitch":false}
                """.trimIndent(),
            ),
        )
        // Page 2: a tombstone for a blood reading, hasMore=false.
        server.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"serverTime":"2026-06-02T18:01:00Z",
                 "changes":[
                   {"collection":"bloodReadings","id":"br-1","status":"ARCHIVED",
                    "lastUpdate":"2026-06-02T18:01:00Z","doc":null}
                 ],
                 "nextCursor":"cur-3","hasMore":false,"killSwitch":false}
                """.trimIndent(),
            ),
        )

        // Seed an existing blood reading so the tombstone has a row to archive.
        mirror.upsert(
            MirrorTables.BLOOD_READINGS,
            MirrorRowData("br-1", """{"v":1}""", 0, "ACTIVE", false, "SYNCED"),
        )

        val result = engine.pull()

        assertEquals(2, result.pages)
        assertEquals(2, result.applied)
        assertEquals(false, result.killSwitch)

        // ACTIVE medication upserted.
        val med = mirror.getRow(MirrorTables.MEDICATIONS, "med-1")!!
        assertEquals("ACTIVE", med.status)
        assertTrue(med.payloadJson.contains("Aspirin"))
        assertEquals("SYNCED", med.syncState)

        // Tombstone archived the blood reading.
        val br = mirror.getRow(MirrorTables.BLOOD_READINGS, "br-1")!!
        assertEquals("ARCHIVED", br.status)

        // First request carried no cursor; cursor persisted to the last page's.
        val first = server.takeRequest()
        assertTrue(first.path!!.contains("schemaVersion=1"))
        assertTrue("since absent on initial sync", !first.path!!.contains("since="))
        assertEquals("cur-3", syncState.get()!!.cursor)
    }

    @Test
    fun `older incoming change is rejected by LWW`() = runTest {
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-1", """{"name":"new"}""", lastUpdate = 5_000, "ACTIVE", false, "SYNCED"),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"changes":[
                  {"collection":"medications","id":"med-1","status":"ACTIVE",
                   "lastUpdate":"1970-01-01T00:00:01Z","doc":{"name":"stale"}}
                ],"nextCursor":"c","hasMore":false,"killSwitch":false}
                """.trimIndent(),
            ),
        )

        val result = engine.pull()

        assertEquals(1, result.rejected)
        assertEquals(0, result.applied)
        // Local row untouched.
        assertTrue(mirror.getRow(MirrorTables.MEDICATIONS, "med-1")!!.payloadJson.contains("new"))
    }

    @Test
    fun `dirty local edit that loses LWW is discarded and signals updated-elsewhere`() = runTest {
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-1", """{"name":"localEdit"}""", lastUpdate = 1_000, "ACTIVE", dirty = true, "PENDING"),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"changes":[
                  {"collection":"medications","id":"med-1","status":"ACTIVE",
                   "lastUpdate":"2026-06-02T18:00:00Z","doc":{"name":"serverWins"}}
                ],"nextCursor":"c","hasMore":false,"killSwitch":false}
                """.trimIndent(),
            ),
        )

        val signals = mutableListOf<SyncEngine.UpdatedElsewhere>()
        backgroundScope.launch(Dispatchers.Unconfined) {
            engine.updatedElsewhere.collect { signals += it }
        }

        val result = engine.pull()

        assertEquals(1, result.discardedLocal)
        val row = mirror.getRow(MirrorTables.MEDICATIONS, "med-1")!!
        assertTrue(row.payloadJson.contains("serverWins"))
        assertEquals(false, row.dirty)
        // The "updated elsewhere" note fired for the discarded local edit. Assert
        // by content (the Unconfined collector's exact emission timing under
        // virtual time is not deterministic across full-suite vs isolated runs).
        assertTrue(
            "expected updated-elsewhere signal for med-1, got $signals",
            signals.contains(SyncEngine.UpdatedElsewhere(MirrorTables.MEDICATIONS, "med-1")),
        )
    }

    @Test
    fun `schemaVersion mismatch triggers wipe and restart from empty cursor`() = runTest {
        // First response advertises schemaVersion=2 ⇒ wipe + restart.
        server.enqueue(
            MockResponse().setBody(
                """{"schemaVersion":2,"changes":[],"nextCursor":null,"hasMore":false,"killSwitch":false}""",
            ),
        )
        // After wipe, the engine re-requests; serve a clean v1 page.
        server.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"changes":[
                  {"collection":"medications","id":"m","status":"ACTIVE",
                   "lastUpdate":"2026-06-02T18:00:00Z","doc":{"n":1}}
                ],"nextCursor":"done","hasMore":false,"killSwitch":false}
                """.trimIndent(),
            ),
        )

        val result = engine.pull()

        assertTrue(result.wiped)
        assertEquals(1, wiper.wipes)
        assertEquals("done", syncState.get()!!.cursor)
        assertEquals(1, result.applied)
    }

    @Test
    fun `killSwitch true sets the disable flag and stops pulling`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"schemaVersion":1,"changes":[],"nextCursor":"x","hasMore":true,"killSwitch":true}""",
            ),
        )

        val result = engine.pull()

        assertTrue(result.killSwitch)
        assertTrue(flags.writes.contains(true))
        // Only one request made despite hasMore=true (we bail on kill-switch).
        assertEquals(1, server.requestCount)
    }
}
