package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.db.dao.MedicationAdherenceDao
import com.gte619n.healthfitness.data.db.entity.MedicationAdherenceEntity
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

    private lateinit var repository: AdherenceRepository
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
            diagnostics = com.gte619n.healthfitness.data.sync.SyncDiagnostics(),
            io = Dispatchers.Unconfined,
            clock = { 1_000L },
        )
        val support = MirrorRepositorySupport(
            mirror = mirror,
            outbox = outbox,
            killSwitch = KillSwitchGate { false },
            drainTrigger = DrainTrigger { drains++ },
        )
        repository = AdherenceRepository(
            support,
            io.mockk.mockk(relaxed = true),   // MedicationAdherenceDao (unused by log/undo)
            MedsTestMoshi.instance,
            Dispatchers.Unconfined,
        )
    }

    @Test
    fun `offline logDose writes a PENDING mirror row and enqueues a CREATE`() = runBlocking {
        val takenAt = Instant.parse("2026-05-30T08:00:00Z")
        repository.logDose("m1", TimeWindow.MORNING, takenAt = takenAt, dose = 200.0)

        val id = AdherenceRepository.adherenceId(
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

        val id = AdherenceRepository.adherenceId("m1", date, TimeWindow.EVENING)
        val row = mirror.getRow(MirrorTables.MEDICATION_ADHERENCE, id)!!
        assertEquals("ARCHIVED", row.status)
        val ops = outboxDao.listByEntity(id).map { it.op }
        assertTrue("an undo enqueues a DELETE", ops.contains(OutboxOp.DELETE.name))
    }

    // ---- window-set readers: flat local rows + pulled server day-rows ----------

    @Test
    fun `takenWindowsFor unions flat local rows with pulled server day-rows`() = runBlocking {
        val date = LocalDate.of(2026, 9, 5)
        val repo = repositoryWith(
            flatRow("m1", date, TimeWindow.MORNING, taken = true),
            dayRow("m2", date, """{"window":"MORNING"},{"window":"EVENING","missed":true}"""),
            dayRow("m3", date.minusDays(1), """{"window":"MORNING"}"""),  // other day: ignored
        )

        assertEquals(
            setOf("m1" to TimeWindow.MORNING, "m2" to TimeWindow.MORNING),
            repo.takenWindowsFor(date),
        )
        // recorded additionally counts the remote MISSED marker.
        assertEquals(
            setOf("m1" to TimeWindow.MORNING, "m2" to TimeWindow.MORNING, "m2" to TimeWindow.EVENING),
            repo.recordedWindowsFor(date),
        )
    }

    @Test
    fun `local undo tombstone beats a stale server day-row`() = runBlocking {
        val date = LocalDate.of(2026, 9, 5)
        val repo = repositoryWith(
            // Undone on this device (tombstoned flat row) …
            flatRow("m1", date, TimeWindow.MORNING, taken = true, status = "ARCHIVED"),
            // … while the pulled server day-row still says taken: local wins.
            dayRow("m1", date, """{"window":"MORNING"}"""),
        )

        assertEquals(emptySet<Pair<String, TimeWindow>>(), repo.takenWindowsFor(date))
    }

    private fun repositoryWith(vararg rows: MedicationAdherenceEntity): AdherenceRepository {
        val dao = io.mockk.mockk<MedicationAdherenceDao>(relaxed = true)
        io.mockk.every { dao.observeAll() } returns kotlinx.coroutines.flow.flowOf(rows.toList())
        return AdherenceRepository(
            MirrorRepositorySupport(
                mirror = mirror,
                outbox = io.mockk.mockk(relaxed = true),
                killSwitch = KillSwitchGate { false },
                drainTrigger = DrainTrigger { },
            ),
            dao,
            MedsTestMoshi.instance,
            Dispatchers.Unconfined,
        )
    }

    private fun flatRow(
        med: String,
        date: LocalDate,
        window: TimeWindow,
        taken: Boolean,
        status: String = "ACTIVE",
    ) = MedicationAdherenceEntity(
        id = AdherenceRepository.adherenceId(med, date, window),
        payloadJson = """{"medicationId":"$med","date":"$date","window":"${window.name}","taken":$taken}""",
        lastUpdate = 1L, status = status, dirty = false, syncState = "SYNCED",
    )

    /** A row as the sync pull lands it: composite `med/date` id, server day payload. */
    private fun dayRow(med: String, date: LocalDate, dosesJson: String) = MedicationAdherenceEntity(
        id = "$med/$date",
        payloadJson = """{"date":"$date","doses":[$dosesJson],"notes":null}""",
        lastUpdate = 1L, status = "ACTIVE", dirty = false, syncState = "SYNCED",
    )
}
