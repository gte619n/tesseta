package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.sync.DrainTrigger
import com.gte619n.healthfitness.data.sync.FakeMirrorOps
import com.gte619n.healthfitness.data.sync.FakeOutboxDao
import com.gte619n.healthfitness.data.sync.KillSwitchGate
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.sync.fakeDeviceIdProvider
import com.gte619n.healthfitness.domain.medications.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * IMPL-AND-20 (#24) — offline-capable adherence logging.
 *
 * `logDose`/`undoDose` no longer hit the network synchronously: each writes an
 * optimistic `medicationAdherence` mirror row (keyed by `med/date/window`) and
 * enqueues an outbox mutation that the drain replays to the adherence endpoints.
 */
class AdherenceRepositoryTest {

    private lateinit var repository: DefaultAdherenceRepository
    private lateinit var mirror: FakeMirrorOps
    private lateinit var outboxDao: FakeOutboxDao
    private var drains = 0

    @Before
    fun setUp() {
        mirror = FakeMirrorOps()
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
        repository = DefaultAdherenceRepository(support, MedsTestMoshi.instance, Dispatchers.Unconfined)
    }

    @Test
    fun `offline logDose writes a PENDING mirror row and enqueues a CREATE`() = runBlocking {
        val takenAt = Instant.parse("2026-05-30T08:00:00Z")
        repository.logDose("m1", TimeWindow.MORNING, takenAt = takenAt, dose = 200.0)

        val id = DefaultAdherenceRepository.adherenceId(
            "m1",
            takenAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
            TimeWindow.MORNING,
        )
        val row = mirror.getRow(MirrorTables.MEDICATION_ADHERENCE, id)!!
        assertTrue("optimistic log is dirty", row.dirty)
        assertEquals("PENDING", row.syncState)
        assertTrue(row.payloadJson.contains("\"window\":\"MORNING\""))

        val queued = outboxDao.listByEntity(id).single()
        assertEquals(OutboxOp.CREATE.name, queued.op)
        assertEquals(MirrorTables.MEDICATION_ADHERENCE, queued.entityTable)
        assertTrue("drain requested", drains >= 1)
    }

    @Test
    fun `offline undoDose tombstones the row and enqueues a DELETE`() = runBlocking {
        val date = LocalDate.of(2026, 5, 30)
        // Pre-seed an active log so the undo has a row to tombstone.
        repository.logDose("m1", TimeWindow.EVENING, takenAt = date.atTime(20, 0).atZone(java.time.ZoneId.systemDefault()).toInstant())
        repository.undoDose("m1", date, TimeWindow.EVENING)

        val id = DefaultAdherenceRepository.adherenceId("m1", date, TimeWindow.EVENING)
        val row = mirror.getRow(MirrorTables.MEDICATION_ADHERENCE, id)!!
        assertEquals("ARCHIVED", row.status)
        val ops = outboxDao.listByEntity(id).map { it.op }
        assertTrue("an undo enqueues a DELETE", ops.contains(OutboxOp.DELETE.name))
    }
}
