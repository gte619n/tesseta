package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gte619n.healthfitness.data.db.DbKeystore
import com.gte619n.healthfitness.data.db.HfDatabase
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.db.entity.SyncRowState
import com.gte619n.healthfitness.data.db.entity.SyncRowStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * IMPL-AND-20 (Phase 7) — instrumented airplane-mode CRUD → reconnect → server
 * state end-to-end test (Definition of Done #2).
 *
 * ⚠️ REQUIRES AN EMULATOR OR DEVICE. It uses a **real** SQLCipher-encrypted
 * [HfDatabase] (native libs + Android Keystore) and a real [MirrorStore] over the
 * 21 typed DAOs, plus a **real** [OutboxRepository] + [RestOutboxReplayClient]
 * driving an [OkHttpClient] against a [MockWebServer] standing in for the backend.
 * None of this runs in a JVM unit test or in the agent sandbox. Run with:
 *
 *     ./gradlew :core-data:connectedDebugAndroidTest
 *
 * Scenarios (mapped to the spec's Definition of Done / Phase 4 & 7 verification):
 *  - [offline_create_then_drain_reaches_server_with_idempotency_and_origin_headers]
 *    proves an offline create is durably queued in the encrypted outbox, then on
 *    reconnect replays to the correct per-domain endpoint (via
 *    [OutboxEndpointRegistry]) carrying `Idempotency-Key` + `X-HF-Origin-Device` +
 *    the client-minted `id`, and the local row flips to SYNCED adopting the server
 *    `lastUpdate` (DoD #2; Phase 4 verification 3).
 *  - [offline_create_then_edit_then_delete_collapses_to_a_noop_end_to_end]
 *    proves the outbox reducer (D7) collapses a create→edit→delete chain that never
 *    reached the server to nothing: zero requests are made and the optimistic local
 *    row is removed (DoD #2 net-effect; spec "create→edit→delete collapses").
 *  - [offline_create_then_edit_drains_as_a_single_merged_create] proves a
 *    create→edit chain collapses to one merged CREATE carrying the latest payload
 *    (the create's idempotency key is preserved).
 */
@RunWith(AndroidJUnit4::class)
class SyncE2ETest {

    private lateinit var context: Context
    private lateinit var keystore: DbKeystore
    private lateinit var db: HfDatabase
    private lateinit var mirror: MirrorStore
    private lateinit var server: MockWebServer
    private lateinit var replay: RestOutboxReplayClient
    private lateinit var deviceId: DeviceIdProvider
    private lateinit var outbox: OutboxRepository

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean slate: drop any prior DB file + Keystore-wrapped key blob.
        context.getDatabasePath(HfDatabase.DB_NAME).parentFile
            ?.listFiles { f -> f.name.startsWith(HfDatabase.DB_NAME) }
            ?.forEach { it.delete() }
        keystore = DbKeystore(context)
        db = HfDatabase.build(context, keystore)
        mirror = mirrorStore(db)

        server = MockWebServer()
        server.start()
        replay = RestOutboxReplayClient(
            client = OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .build(),
            moshi = moshi,
            baseUrl = server.url("/").toString(),
        )
        deviceId = DeviceIdProvider(context)
        outbox = OutboxRepository(
            outboxDao = db.outboxDao(),
            mirror = mirror,
            replay = replay,
            deviceIdProvider = deviceId,
            io = Dispatchers.IO,
            clock = { System.currentTimeMillis() },
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
        server.shutdown()
    }

    /** Write an optimistic PENDING mirror row exactly as Phase 5's repositories do. */
    private suspend fun optimisticCreate(table: String, id: String, payloadJson: String) {
        mirror.upsert(
            table,
            MirrorRowData(
                id = id,
                payloadJson = payloadJson,
                lastUpdate = System.currentTimeMillis(),
                status = SyncRowStatus.ACTIVE.name,
                dirty = true,
                syncState = SyncRowState.PENDING.name,
            ),
        )
    }

    @Test
    fun offline_create_then_drain_reaches_server_with_idempotency_and_origin_headers() = runBlocking {
        val id = "med-e2e-1"
        val payload = """{"id":"$id","name":"Aspirin","dose":"81mg"}"""

        // (a) "Airplane mode": the MockWebServer is queued with NO response yet, so
        // a drain attempt would fail. We model offline as "do not drain"; the write
        // is enqueued and lives durably in the encrypted outbox.
        optimisticCreate(MirrorTables.MEDICATIONS, id, payload)
        val mutationId = outbox.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, id, payload)

        // Optimistic mirror row is visible + marked PENDING while offline.
        val pending = mirror.getRow(MirrorTables.MEDICATIONS, id)!!
        assertEquals(SyncRowState.PENDING.name, pending.syncState)
        assertTrue(pending.dirty)
        // Durably queued: the outbox row survives in the encrypted DB.
        assertEquals(1, db.outboxDao().listByEntity(id).size)

        // (b) "Reconnect": bring the server up and drain.
        server.enqueue(
            MockResponse().setBody("""{"id":"$id","lastUpdate":"2026-06-02T18:00:00Z"}"""),
        )
        val result = outbox.drain()
        assertEquals(1, result.sent)
        assertEquals(0, result.failed)

        // The replayed request reached the correct per-domain endpoint with the
        // idempotency + origin-device headers and the client-minted id.
        val recorded: RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertTrue(
            "blood/med endpoint resolved via OutboxEndpointRegistry, got ${recorded.path}",
            recorded.path!!.endsWith("/api/me/medications"),
        )
        assertEquals(mutationId, recorded.getHeader("Idempotency-Key"))
        assertEquals(deviceId.deviceId(), recorded.getHeader("X-HF-Origin-Device"))
        assertTrue("body carries client id", recorded.body.readUtf8().contains("\"id\":\"$id\""))

        // (c) The local row flips to SYNCED + clean and adopts the server lastUpdate.
        val synced = mirror.getRow(MirrorTables.MEDICATIONS, id)!!
        assertEquals(SyncRowState.SYNCED.name, synced.syncState)
        assertFalse(synced.dirty)
        assertEquals(
            Instant.parse("2026-06-02T18:00:00Z").toEpochMilli(),
            synced.lastUpdate,
        )
        // Outbox emptied for the entity.
        assertTrue(db.outboxDao().listByEntity(id).isEmpty())
    }

    @Test
    fun offline_create_then_edit_then_delete_collapses_to_a_noop_end_to_end() = runBlocking {
        val id = "med-e2e-collapse"

        // Offline: create, then edit, then delete the SAME entity. None ever reaches
        // the server, so the reducer must collapse the whole chain to nothing.
        optimisticCreate(MirrorTables.MEDICATIONS, id, """{"id":"$id","name":"v1"}""")
        outbox.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, id, """{"id":"$id","name":"v1"}""")

        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData(id, """{"id":"$id","name":"v2"}""", System.currentTimeMillis(),
                SyncRowStatus.ACTIVE.name, true, SyncRowState.PENDING.name),
        )
        outbox.enqueue(OutboxOp.UPDATE, MirrorTables.MEDICATIONS, id, """{"id":"$id","name":"v2"}""")

        mirror.markArchived(MirrorTables.MEDICATIONS, id, System.currentTimeMillis())
        outbox.enqueue(OutboxOp.DELETE, MirrorTables.MEDICATIONS, id, null)

        // Three queued mutations for the entity, all persisted in the encrypted DB.
        assertEquals(3, db.outboxDao().listByEntity(id).size)

        // Reconnect + drain: server must never be hit because the net effect is nil.
        val result = outbox.drain()

        assertEquals(0, result.sent)
        assertEquals(1, result.collapsed)
        assertEquals("no request should reach the server for a create→…→delete", 0, server.requestCount)
        // The optimistic local row is hard-removed (it never existed on the server).
        assertNull(mirror.getRow(MirrorTables.MEDICATIONS, id))
        // Outbox emptied for the entity.
        assertTrue(db.outboxDao().listByEntity(id).isEmpty())
    }

    @Test
    fun offline_create_then_edit_drains_as_a_single_merged_create() = runBlocking {
        val id = "med-e2e-merge"

        optimisticCreate(MirrorTables.MEDICATIONS, id, """{"id":"$id","name":"v1"}""")
        val createMutationId =
            outbox.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, id, """{"id":"$id","name":"v1"}""")

        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData(id, """{"id":"$id","name":"v2"}""", System.currentTimeMillis(),
                SyncRowStatus.ACTIVE.name, true, SyncRowState.PENDING.name),
        )
        outbox.enqueue(OutboxOp.UPDATE, MirrorTables.MEDICATIONS, id, """{"id":"$id","name":"v2"}""")

        // Always answer with a 200 so a single POST succeeds.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""{"id":"$id","lastUpdate":"2026-06-02T19:00:00Z"}""")
        }

        val result = outbox.drain()

        // create+edit ⇒ a SINGLE merged CREATE.
        assertEquals(1, result.sent)
        assertEquals(0, result.collapsed)
        assertEquals(1, server.requestCount)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        // Merged create keeps the CREATE row's mutationId as its idempotency key…
        assertEquals(createMutationId, recorded.getHeader("Idempotency-Key"))
        // …but carries the LATEST payload (the edit, "v2").
        assertTrue("merged create carries latest payload", recorded.body.readUtf8().contains("\"name\":\"v2\""))

        val synced = mirror.getRow(MirrorTables.MEDICATIONS, id)!!
        assertEquals(SyncRowState.SYNCED.name, synced.syncState)
        assertFalse(synced.dirty)
    }

    @Test
    fun failed_replay_keeps_the_mutation_queued_for_retry_with_backoff() = runBlocking {
        val id = "med-e2e-fail"
        optimisticCreate(MirrorTables.MEDICATIONS, id, """{"id":"$id"}""")
        val mutationId = outbox.enqueue(OutboxOp.CREATE, MirrorTables.MEDICATIONS, id, """{"id":"$id"}""")

        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR))

        val result = outbox.drain()

        assertEquals(0, result.sent)
        assertEquals(1, result.failed)
        // Row flagged FAILED for the D11 badge; the mutation survives for retry.
        assertEquals(SyncRowState.FAILED.name, mirror.getRow(MirrorTables.MEDICATIONS, id)!!.syncState)
        val queued = db.outboxDao().listByEntity(id).single()
        assertEquals(mutationId, queued.mutationId)
        assertEquals(1, queued.attempts)
        assertTrue("backoff scheduled into the future", queued.nextAttemptAt > System.currentTimeMillis())
        assertNotNull(queued)
    }
}
