package com.gte619n.healthfitness.data.blood

import com.gte619n.healthfitness.data.db.dao.BloodReadingDao
import com.gte619n.healthfitness.data.db.entity.BloodReadingEntity
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.gte619n.healthfitness.data.sync.DrainTrigger
import com.gte619n.healthfitness.data.sync.FakeMirrorOps
import com.gte619n.healthfitness.data.sync.FakeOutboxDao
import com.gte619n.healthfitness.data.sync.KillSwitchGate
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.sync.fakeDeviceIdProvider
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * IMPL-AND-20 (Phase 5) — Room-backed blood-readings repository contract.
 *
 * The repository now reads from the `bloodReadings` mirror (via a fake DAO backed
 * by the same in-memory [FakeMirrorOps]) and writes optimistically through the
 * outbox. These tests assert the new offline-first behavior on the pure JVM:
 *  - `refresh()` fills the mirror from the network, surfacing on the read Flow.
 *  - an offline `create()` appears INSTANTLY as a PENDING, dirty row and enqueues
 *    a CREATE outbox mutation (no network call) — the spec's required proof.
 *  - `delete()` tombstones the row (drops out of the active Flow) + enqueues DELETE.
 */
class BloodReadingRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BloodApi
    private lateinit var moshi: Moshi

    private lateinit var mirror: FakeMirrorOps
    private lateinit var dao: FakeBloodReadingDao
    private lateinit var outboxDao: FakeOutboxDao
    private var drains = 0

    private lateinit var repo: BloodReadingRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        moshi = Moshi.Builder()
            .add(LocalDateAdapter())
            .add(InstantAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BloodApi::class.java)

        dao = FakeBloodReadingDao()
        mirror = dao.mirror
        outboxDao = FakeOutboxDao()
        val outbox = OutboxRepository(
            outboxDao = outboxDao,
            mirror = mirror,
            replay = io.mockk.mockk(relaxed = true),
            deviceIdProvider = fakeDeviceIdProvider("device-A"),
            io = Dispatchers.Unconfined,
            clock = { 1_000L },
        )
        val support = MirrorRepositorySupport(
            mirror = mirror,
            outbox = outbox,
            killSwitch = KillSwitchGate { false },
            drainTrigger = DrainTrigger { drains++ },
        )
        repo = BloodReadingRepository(api, dao, support, moshi)
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun readingJson(id: String, value: Double) = """
        {
          "readingId": "$id",
          "marker": "LDL",
          "value": $value,
          "unit": "mg/dL",
          "sampleDate": "2026-05-01",
          "labSource": null,
          "notes": null,
          "reference": {
            "unit": "mg/dL",
            "orientation": "LOWER_IS_BETTER",
            "goodThreshold": 100.0,
            "displayMin": 0.0,
            "displayMax": 200.0
          }
        }
    """.trimIndent()

    @Test
    fun `refresh fills the mirror and surfaces on the read flow`() = runTest {
        server.enqueue(MockResponse().setBody("[${readingJson("r1", 82.0)}]"))
        repo.refresh()
        val readings = repo.observeReadings().first()
        assertEquals(1, readings.size)
        assertEquals(BloodMarker.LDL, readings.first().marker)
        assertEquals(82.0, readings.first().value, 0.0001)
        // Computed-field gap (#7): the server `reference` round-trips through Room.
        assertEquals(100.0, readings.first().reference.goodThreshold, 0.0001)
    }

    @Test
    fun `offline create shows up instantly as PENDING and enqueues a CREATE mutation`() = runTest {
        val created = repo.create(
            marker = BloodMarker.LDL,
            value = 82.0,
            unit = null,
            sampleDate = LocalDate.of(2026, 5, 1),
            labSource = null,
            notes = null,
        )

        // No network call was made (offline-first; the outbox owns the replay).
        assertEquals(0, server.requestCount)

        // Appears instantly on the read Flow.
        val readings = repo.observeReadings().first()
        assertEquals(1, readings.size)
        assertEquals(created.readingId, readings.first().readingId)

        // Mirror row is dirty + PENDING (drives the D11 badge in Phase 6).
        val row = mirror.getRow(MirrorTables.BLOOD_READINGS, created.readingId)!!
        assertTrue(row.dirty)
        assertEquals("PENDING", row.syncState)

        // A CREATE mutation was enqueued and a drain requested.
        val queued = outboxDao.listByEntity(created.readingId).single()
        assertEquals(OutboxOp.CREATE.name, queued.op)
        assertTrue(drains >= 1)
    }

    @Test
    fun `delete tombstones the row and enqueues a DELETE mutation`() = runTest {
        server.enqueue(MockResponse().setBody("[${readingJson("r1", 82.0)}]"))
        repo.refresh()
        assertEquals(1, repo.observeReadings().first().size)

        repo.delete("r1")

        assertTrue(repo.observeReadings().first().isEmpty())
        val row = mirror.getRow(MirrorTables.BLOOD_READINGS, "r1")!!
        assertEquals("ARCHIVED", row.status)
        val queued = outboxDao.listByEntity("r1").single()
        assertEquals(OutboxOp.DELETE.name, queued.op)
        assertFalse(queued.payloadJson != null)
    }
}

/**
 * In-memory [BloodReadingDao] that owns a [FakeMirrorOps] (exposed as [mirror])
 * so the support's optimistic writes and this DAO's read Flow observe the SAME
 * store. The Flow re-publishes whenever a write lands via [TrackingMirrorOps].
 */
private class FakeBloodReadingDao : BloodReadingDao {
    private val flow = MutableStateFlow<List<BloodReadingEntity>>(emptyList())
    val mirror = TrackingMirrorOps { publish() }

    private fun publish() {
        flow.value = mirror.rows.entries
            .filter { it.key.startsWith("${MirrorTables.BLOOD_READINGS}:") }
            .map { it.value }
            .filter { it.status != "ARCHIVED" }
            .map { BloodReadingEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }
            .sortedByDescending { it.lastUpdate }
    }

    override fun observeActive(): Flow<List<BloodReadingEntity>> {
        publish()
        return flow
    }

    override suspend fun readingsInRange(fromMillis: Long, toMillis: Long): List<BloodReadingEntity> =
        mirror.rows.entries
            .filter { it.key.startsWith("${MirrorTables.BLOOD_READINGS}:") }
            .map { it.value }
            .filter { it.status != "ARCHIVED" && it.lastUpdate in fromMillis..toMillis }
            .map { BloodReadingEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }
            .sortedByDescending { it.lastUpdate }

    override suspend fun getById(id: String): BloodReadingEntity? =
        mirror.getRow(MirrorTables.BLOOD_READINGS, id)
            ?.let { BloodReadingEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }

    override suspend fun upsert(row: BloodReadingEntity) =
        mirror.upsert(
            MirrorTables.BLOOD_READINGS,
            com.gte619n.healthfitness.data.sync.MirrorRowData(
                row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState,
            ),
        )

    override suspend fun upsertAll(rows: List<BloodReadingEntity>) { rows.forEach { upsert(it) } }
    override suspend fun markArchived(id: String, lastUpdate: Long) =
        mirror.markArchived(MirrorTables.BLOOD_READINGS, id, lastUpdate)
    override suspend fun delete(id: String) = mirror.delete(MirrorTables.BLOOD_READINGS, id)
}

/** [FakeMirrorOps] that fires [onChange] after each mutating op. */
private class TrackingMirrorOps(private val onChange: () -> Unit) : FakeMirrorOps() {
    override suspend fun upsert(table: String, row: com.gte619n.healthfitness.data.sync.MirrorRowData) {
        super.upsert(table, row); onChange()
    }
    override suspend fun markArchived(table: String, id: String, lastUpdate: Long) {
        super.markArchived(table, id, lastUpdate); onChange()
    }
    override suspend fun delete(table: String, id: String) { super.delete(table, id); onChange() }
}
