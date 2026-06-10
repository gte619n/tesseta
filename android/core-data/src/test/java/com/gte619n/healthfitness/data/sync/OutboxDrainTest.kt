package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * IMPL-AND-20 (Phase 4) — outbox drain over MockWebServer.
 *
 * Enqueues an offline create, drains, and asserts the replay request hit the
 * server with the client `id`, the `Idempotency-Key` (= mutationId) and the
 * `X-HF-Origin-Device` headers; then asserts the mirror row flipped to
 * SYNCED+clean adopting the server `lastUpdate`. Also covers the reducer's
 * create→delete no-op collapse and the failure/backoff path. Pure JVM.
 */
class OutboxDrainTest {

    private lateinit var server: MockWebServer
    private lateinit var replay: RestOutboxReplayClient
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var mirror: FakeMirrorOps
    private lateinit var repo: OutboxRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        replay = RestOutboxReplayClient(
            client = OkHttpClient(),
            moshi = SyncTestMoshi.instance,
            baseUrl = server.url("/").toString(),
        )
        outboxDao = FakeOutboxDao()
        mirror = FakeMirrorOps()
        repo = OutboxRepository(
            outboxDao = outboxDao,
            mirror = mirror,
            replay = replay,
            deviceIdProvider = fakeDeviceIdProvider("device-A"),
            io = Dispatchers.Unconfined,
            clock = { now },
        )
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `offline create drains with idempotency and origin-device headers and client id`() = runTest {
        // Optimistic local row (as Phase 5 will write before enqueue).
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-1", """{"id":"med-1","name":"Aspirin"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        val mutationId = repo.enqueue(
            op = OutboxOp.CREATE,
            table = MirrorTables.MEDICATIONS,
            entityId = "med-1",
            payloadJson = """{"id":"med-1","name":"Aspirin"}""",
        )

        server.enqueue(
            MockResponse().setBody("""{"id":"med-1","lastUpdate":"2026-06-02T18:00:00Z"}"""),
        )

        val result = repo.drain()

        assertEquals(1, result.sent)
        assertEquals(0, result.failed)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/api/me/medications"))
        assertEquals(mutationId, recorded.getHeader("Idempotency-Key"))
        assertEquals("device-A", recorded.getHeader("X-HF-Origin-Device"))
        assertTrue("body carries client id", recorded.body.readUtf8().contains("\"id\":\"med-1\""))

        // Mirror row reconciled: SYNCED, clean, adopts server lastUpdate.
        val row = mirror.getRow(MirrorTables.MEDICATIONS, "med-1")!!
        assertEquals("SYNCED", row.syncState)
        assertEquals(false, row.dirty)
        assertEquals(
            java.time.Instant.parse("2026-06-02T18:00:00Z").toEpochMilli(),
            row.lastUpdate,
        )
        // Outbox emptied for the entity.
        assertTrue(outboxDao.listByEntity("med-1").isEmpty())
    }

    @Test
    fun `create then delete collapses to a no-op and never hits the server`() = runTest {
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-2", """{"id":"med-2"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "med-2", """{"id":"med-2"}""")
        repo.enqueue(OutboxOp.DELETE, MirrorTables.MEDICATIONS, "med-2", null)

        val result = repo.drain()

        assertEquals(0, result.sent)
        assertEquals(1, result.collapsed)
        assertEquals(0, server.requestCount)
        // Optimistic local row removed (it never existed on the server).
        assertTrue(mirror.getRow(MirrorTables.MEDICATIONS, "med-2") == null)
        assertTrue(outboxDao.listByEntity("med-2").isEmpty())
    }

    @Test
    fun `failed replay marks row FAILED and schedules exponential backoff`() = runTest {
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-3", """{"id":"med-3"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        val mutationId = repo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "med-3", """{"id":"med-3"}""")

        server.enqueue(MockResponse().setResponseCode(500))

        val result = repo.drain()

        assertEquals(0, result.sent)
        assertEquals(1, result.failed)
        assertEquals("FAILED", mirror.getRow(MirrorTables.MEDICATIONS, "med-3")!!.syncState)

        val queued = outboxDao.listByEntity("med-3").single()
        assertEquals(1, queued.attempts)
        assertEquals(mutationId, queued.mutationId)
        // First-attempt backoff = base (30s) added to the drain clock.
        assertEquals(now + OutboxRepository.BASE_BACKOFF_MILLIS, queued.nextAttemptAt)
    }

    @Test
    fun `nutrition entry replay unwraps the date-wrapped payload to the bare entry`() = runTest {
        // The mirror stores a date-wrapped row so the day view can reassemble by
        // date; the replay must send the inner entry (meal/foodName at top level)
        // so the backend's EntryPatchRequest actually applies the change.
        val wrapped =
            """{"date":"2026-06-02","entry":{"entryId":"e1","meal":"snack","foodName":"Eggs"}}"""
        mirror.upsert(
            MirrorTables.NUTRITION_ENTRIES,
            MirrorRowData("2026-06-02/e1", wrapped, now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.UPDATE, MirrorTables.NUTRITION_ENTRIES, "2026-06-02/e1", wrapped)
        server.enqueue(
            MockResponse().setBody("""{"entryId":"e1","lastUpdate":"2026-06-02T18:00:00Z"}"""),
        )

        repo.drain()

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertTrue(recorded.path!!.endsWith("/api/me/nutrition/2026-06-02/entries/e1"))
        val body = recorded.body.readUtf8()
        assertTrue("bare entry has top-level meal", body.contains("\"meal\":\"snack\""))
        assertTrue("wrapper stripped (no nested entry/date)", !body.contains("\"entry\":"))
    }

    @Test
    fun `terminal 4xx parks the mutation instead of retrying forever`() = runTest {
        // A deterministic server rejection (e.g. the IMPL-16 completion upsert
        // 400/404 after a concurrent program rewrite) replays identically every
        // time — the row must leave the automatic drain, but stay queued (with
        // its payload) and FAILED-flagged rather than being dropped.
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-4", """{"id":"med-4"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "med-4", """{"id":"med-4"}""")

        server.enqueue(MockResponse().setResponseCode(400))

        val result = repo.drain()

        assertEquals(0, result.sent)
        assertEquals(1, result.failed)
        assertEquals("FAILED", mirror.getRow(MirrorTables.MEDICATIONS, "med-4")!!.syncState)

        val parked = outboxDao.listByEntity("med-4").single()
        assertEquals(1, parked.attempts)
        assertEquals(OutboxRepository.PARKED_NEXT_ATTEMPT, parked.nextAttemptAt)

        // Even far past any backoff ceiling, an automatic drain skips the row.
        now += 10 * OutboxRepository.MAX_BACKOFF_MILLIS
        repo.drain()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `manual rearm makes a parked mutation due and a retry can succeed`() = runTest {
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-5", """{"id":"med-5"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "med-5", """{"id":"med-5"}""")
        server.enqueue(MockResponse().setResponseCode(404))
        repo.drain()
        assertEquals(OutboxRepository.PARKED_NEXT_ATTEMPT, outboxDao.listByEntity("med-5").single().nextAttemptAt)

        // The D11 "changes failed — retry" lever: re-arm, then drain again.
        repo.rearmFailed()
        server.enqueue(
            MockResponse().setBody("""{"id":"med-5","lastUpdate":"2026-06-02T18:00:00Z"}"""),
        )
        val retried = repo.drain()

        assertEquals(1, retried.sent)
        assertEquals("SYNCED", mirror.getRow(MirrorTables.MEDICATIONS, "med-5")!!.syncState)
        assertTrue(outboxDao.listByEntity("med-5").isEmpty())
    }

    @Test
    fun `rate-limit 429 backs off rather than parking`() = runTest {
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-6", """{"id":"med-6"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "med-6", """{"id":"med-6"}""")

        server.enqueue(MockResponse().setResponseCode(429))
        repo.drain()

        val queued = outboxDao.listByEntity("med-6").single()
        assertEquals(now + OutboxRepository.BASE_BACKOFF_MILLIS, queued.nextAttemptAt)
    }

    @Test
    fun `backoff is exponential and capped`() {
        assertEquals(30_000L, OutboxRepository.backoffMillis(1))
        assertEquals(60_000L, OutboxRepository.backoffMillis(2))
        assertEquals(120_000L, OutboxRepository.backoffMillis(3))
        assertEquals(OutboxRepository.MAX_BACKOFF_MILLIS, OutboxRepository.backoffMillis(40))
    }
}
