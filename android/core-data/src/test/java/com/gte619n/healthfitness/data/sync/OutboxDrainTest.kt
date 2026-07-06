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
    private lateinit var diagnostics: SyncDiagnostics
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
        diagnostics = SyncDiagnostics()
        repo = OutboxRepository(
            outboxDao = outboxDao,
            mirror = mirror,
            replay = replay,
            deviceIdProvider = fakeDeviceIdProvider("device-A"),
            diagnostics = diagnostics,
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
    fun `a parked replay records the server message into diagnostics (Workstream B)`() = runTest {
        // WORKOUT_SCHEDULED keeps the park-on-terminal behavior (it has a bespoke
        // restore flow), so a terminal 4xx surfaces its reason instead of being
        // swallowed. (Other tables self-heal terminals; see the reconcile tests.)
        mirror.upsert(
            MirrorTables.WORKOUT_SCHEDULED,
            MirrorRowData("p1/s1", """{"id":"p1/s1"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.UPDATE, MirrorTables.WORKOUT_SCHEDULED, "p1/s1", """{"id":"p1/s1"}""")

        // Spring-style error body; the reason must reach the user-facing detail
        // instead of being swallowed into a silent FAILED row.
        server.enqueue(
            MockResponse().setResponseCode(422).setBody("""{"message":"session no longer scheduled"}"""),
        )

        repo.drain()

        val last = diagnostics.lastError
        assertTrue("an error was recorded", last.value != null)
        assertEquals(422, last.value!!.httpCode)
        assertEquals(true, last.value!!.terminal)
        assertEquals(MirrorTables.WORKOUT_SCHEDULED, last.value!!.table)
        assertEquals("session no longer scheduled", last.value!!.message)
    }

    @Test
    fun `a clean drain clears the surfaced last error`() = runTest {
        // Seed a prior error, then a successful drain should clear the banner detail.
        diagnostics.record(source = "test", message = "stale", table = MirrorTables.MEDICATIONS)
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-ok", """{"id":"med-ok"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "med-ok", """{"id":"med-ok"}""")
        server.enqueue(MockResponse().setBody("""{"id":"med-ok","lastUpdate":"2026-06-02T18:00:00Z"}"""))

        repo.drain()

        assertTrue("clean drain clears last error", diagnostics.lastError.value == null)
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
    fun `nutrition entry create surfaces the client entryId as id so the server honors it`() = runTest {
        // meal-sync-error: the mirror carries the client-minted id as `entryId`,
        // but the backend create binds it from `id`. The replay must surface it as
        // `id` so an offline create (e.g. a one-tap re-log) lands under the SAME
        // id a later meal-move PATCHes — otherwise the server mints its own and
        // the move 404s "entry not found".
        val wrapped =
            """{"date":"2026-06-02","entry":{"entryId":"relog-1","meal":"BREAKFAST","foodName":"Shake"}}"""
        mirror.upsert(
            MirrorTables.NUTRITION_ENTRIES,
            MirrorRowData("2026-06-02/relog-1", wrapped, now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.NUTRITION_ENTRIES, "2026-06-02/relog-1", wrapped)
        server.enqueue(
            MockResponse().setBody("""{"entryId":"relog-1","lastUpdate":"2026-06-02T18:00:00Z"}"""),
        )

        repo.drain()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/api/me/nutrition/2026-06-02/entries"))
        val body = recorded.body.readUtf8()
        assertTrue("client id surfaced as id", body.contains("\"id\":\"relog-1\""))
        assertTrue("wrapper stripped", !body.contains("\"entry\":"))
    }

    @Test
    fun `create replay surfaces the client id as id for a flat domain-id table`() = runTest {
        // meal-sync-error (generalized): the per-domain mirror DTO carries its id
        // as e.g. `locationId`, but every backend create binds `id`
        // (resolveId(body.id())). The replay must surface the client-minted entity
        // id as `id` so the server honors it — else it mints its own and a later
        // edit/delete 404s. wireBody only special-cased nutrition before.
        val payload = """{"locationId":"loc-9","name":"Downtown Gym"}"""
        mirror.upsert(
            MirrorTables.LOCATIONS,
            MirrorRowData("loc-9", payload, now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.LOCATIONS, "loc-9", payload)
        server.enqueue(MockResponse().setBody("""{"locationId":"loc-9","lastUpdate":"2026-06-02T18:00:00Z"}"""))

        repo.drain()

        val body = server.takeRequest().body.readUtf8()
        assertTrue("client id surfaced as id", body.contains("\"id\":\"loc-9\""))
    }

    @Test
    fun `create replay surfaces the child id as id for a composite-id table`() = runTest {
        // A composite entityId `"<parent>/<child>"` (here a goal phase): the id the
        // server binds is the CHILD id (the trailing segment), which must reach the
        // body as `id`.
        val payload = """{"phaseId":"ph-2","goalId":"g-1","title":"Build base"}"""
        mirror.upsert(
            MirrorTables.GOAL_PHASES,
            MirrorRowData("g-1/ph-2", payload, now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.GOAL_PHASES, "g-1/ph-2", payload)
        server.enqueue(MockResponse().setBody("""{"phaseId":"ph-2","lastUpdate":"2026-06-02T18:00:00Z"}"""))

        repo.drain()

        val recorded = server.takeRequest()
        assertTrue("posts to the phases collection", recorded.path!!.endsWith("/api/me/goals/g-1/phases"))
        assertTrue("composite child id surfaced as id", recorded.body.readUtf8().contains("\"id\":\"ph-2\""))
    }

    @Test
    fun `terminal 4xx parks a WORKOUT_SCHEDULED mutation instead of retrying forever`() = runTest {
        // A deterministic server rejection (the IMPL-17 completion upsert 400/404
        // after a concurrent program rewrite deleted the scheduled session) replays
        // identically every time. WORKOUT_SCHEDULED keeps the parked row (with its
        // payload, FAILED-flagged) so the logger can restore it — it is NOT dropped.
        mirror.upsert(
            MirrorTables.WORKOUT_SCHEDULED,
            MirrorRowData("p2/s2", """{"id":"p2/s2"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.UPDATE, MirrorTables.WORKOUT_SCHEDULED, "p2/s2", """{"id":"p2/s2"}""")

        server.enqueue(MockResponse().setResponseCode(400))

        val result = repo.drain()

        assertEquals(0, result.sent)
        assertEquals(1, result.failed)
        assertEquals("FAILED", mirror.getRow(MirrorTables.WORKOUT_SCHEDULED, "p2/s2")!!.syncState)

        val parked = outboxDao.listByEntity("p2/s2").single()
        assertEquals(1, parked.attempts)
        assertEquals(OutboxRepository.PARKED_NEXT_ATTEMPT, parked.nextAttemptAt)

        // Even far past any backoff ceiling, an automatic drain skips the row.
        now += 10 * OutboxRepository.MAX_BACKOFF_MILLIS
        repo.drain()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `manual rearm makes a parked WORKOUT_SCHEDULED mutation due and a retry can succeed`() = runTest {
        mirror.upsert(
            MirrorTables.WORKOUT_SCHEDULED,
            MirrorRowData("p3/s3", """{"id":"p3/s3"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.UPDATE, MirrorTables.WORKOUT_SCHEDULED, "p3/s3", """{"id":"p3/s3"}""")
        server.enqueue(MockResponse().setResponseCode(404))
        repo.drain()
        assertEquals(OutboxRepository.PARKED_NEXT_ATTEMPT, outboxDao.listByEntity("p3/s3").single().nextAttemptAt)

        // The D11 "changes failed — retry" lever: re-arm, then drain again.
        repo.rearmFailed()
        server.enqueue(
            MockResponse().setBody("""{"id":"p3/s3","lastUpdate":"2026-06-02T18:00:00Z"}"""),
        )
        val retried = repo.drain()

        assertEquals(1, retried.sent)
        assertEquals("SYNCED", mirror.getRow(MirrorTables.WORKOUT_SCHEDULED, "p3/s3")!!.syncState)
        assertTrue(outboxDao.listByEntity("p3/s3").isEmpty())
    }

    @Test
    fun `terminal 4xx self-heals a non-workout edit instead of parking`() = runTest {
        // #1: a rejected edit converges to server truth rather than nagging forever.
        // Drop the doomed mutation, clear the FAILED/dirty state, and reset the LWW
        // clock (lastUpdate=0) so the next pull re-applies the authoritative value.
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-x", """{"id":"med-x","name":"edited"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.UPDATE, MirrorTables.MEDICATIONS, "med-x", """{"id":"med-x","name":"edited"}""")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"invalid"}"""))

        val result = repo.drain()

        assertEquals(0, result.failed) // not a nagging failure
        assertTrue("doomed mutation dropped", outboxDao.listByEntity("med-x").isEmpty())
        val row = mirror.getRow(MirrorTables.MEDICATIONS, "med-x")!!
        assertEquals("SYNCED", row.syncState) // FAILED badge cleared
        assertEquals(false, row.dirty)        // no longer blocks a refresh
        assertEquals(0L, row.lastUpdate)      // server wins the next pull
    }

    @Test
    fun `terminal 4xx on a non-workout create drops the optimistic row`() = runTest {
        // A rejected create was never persisted server-side ⇒ the optimistic local
        // row is garbage; drop it (rather than leave a permanent FAILED ghost).
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("med-c", """{"id":"med-c"}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        repo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "med-c", """{"id":"med-c"}""")
        server.enqueue(MockResponse().setResponseCode(400))

        repo.drain()

        assertTrue(outboxDao.listByEntity("med-c").isEmpty())
        assertEquals(null, mirror.getRow(MirrorTables.MEDICATIONS, "med-c"))
    }

    @Test
    fun `a no-op parent's orphaned child create is dropped, not sent`() = runTest {
        // #3: Goal G created then deleted offline (never on the server); a phase G/P
        // was created under it. The phase create must NOT be sent — it would 404 on
        // the missing parent and park forever. It's local-only garbage: dropped with
        // its optimistic row.
        mirror.upsert(MirrorTables.GOALS, MirrorRowData("G", """{"id":"G"}""", now, "ACTIVE", dirty = true, "PENDING"))
        repo.enqueue(OutboxOp.CREATE, MirrorTables.GOALS, "G", """{"id":"G"}""")
        repo.enqueue(OutboxOp.DELETE, MirrorTables.GOALS, "G", null)
        mirror.upsert(MirrorTables.GOAL_PHASES, MirrorRowData("G/P", """{"id":"P"}""", now, "ACTIVE", dirty = true, "PENDING"))
        repo.enqueue(OutboxOp.CREATE, MirrorTables.GOAL_PHASES, "G/P", """{"id":"P"}""")

        val result = repo.drain()

        assertEquals("nothing reaches the server", 0, server.requestCount)
        assertEquals(1, result.collapsed) // the goal no-op
        assertEquals(null, mirror.getRow(MirrorTables.GOALS, "G"))
        assertEquals(null, mirror.getRow(MirrorTables.GOAL_PHASES, "G/P"))
        assertTrue(outboxDao.listByEntity("G").isEmpty())
        assertTrue(outboxDao.listByEntity("G/P").isEmpty())
    }

    @Test
    fun `a write that lands mid-drain is preserved, not swept away by cleanup`() = runTest {
        // #2: a second edit to the same entity arrives during the network replay of
        // the first (after the drain snapshotted the chain). Cleanup deletes only the
        // snapshotted rows by id, so the new mutation survives and drains next pass —
        // no silent data loss.
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData("m", """{"id":"m","v":1}""", now, "ACTIVE", dirty = true, "PENDING"),
        )
        lateinit var racingRepo: OutboxRepository
        var midWrite: String? = null
        val racingReplay = object : OutboxReplayClient {
            override suspend fun replay(
                table: String, op: OutboxOp, entityId: String,
                payloadJson: String?, mutationId: String, originDeviceId: String,
            ): Long {
                // A concurrent user edit lands after the chain snapshot.
                midWrite = racingRepo.enqueue(
                    OutboxOp.UPDATE, MirrorTables.MEDICATIONS, "m", """{"id":"m","v":2}""",
                )
                return 111L
            }
        }
        racingRepo = OutboxRepository(
            outboxDao = outboxDao, mirror = mirror, replay = racingReplay,
            deviceIdProvider = fakeDeviceIdProvider("device-A"), diagnostics = diagnostics,
            io = Dispatchers.Unconfined, clock = { now },
        )
        racingRepo.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, "m", """{"id":"m","v":1}""")

        racingRepo.drain()

        val remaining = outboxDao.listByEntity("m")
        assertEquals("the mid-drain write survives", 1, remaining.size)
        assertEquals(midWrite, remaining.single().mutationId)
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
