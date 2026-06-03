package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.db.dao.MedicationAdherenceDao
import com.gte619n.healthfitness.data.db.dao.MedicationDao
import com.gte619n.healthfitness.data.db.entity.MedicationAdherenceEntity
import com.gte619n.healthfitness.data.db.entity.MedicationEntity
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.sync.DrainTrigger
import com.gte619n.healthfitness.data.sync.FakeMirrorOps
import com.gte619n.healthfitness.data.sync.FakeOutboxDao
import com.gte619n.healthfitness.data.sync.KillSwitchGate
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.sync.MirrorRowData
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.sync.fakeDeviceIdProvider
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.DrugRepository
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.LocalDate

/**
 * IMPL-AND-20 (Phase 5) — Room-backed medications repository contract.
 *
 * `list()` now fills the `medications` mirror from the network (one-shot on a cold
 * miss, `GET /api/me/medications` with no status param) and serves/partitions the
 * meds from Room. `create()`/`delete()` are optimistic + outbox (no network). The
 * server-evaluated transitions (`changeDose`/`discontinue`/`reactivate`) and the
 * flat detail `get()` still hit their endpoints.
 */
class MedicationRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultMedicationRepository

    private lateinit var dao: FakeMedicationDao
    private lateinit var adherenceDao: FakeMedicationAdherenceDao
    private lateinit var outboxDao: FakeOutboxDao

    // Mirror-backed meds carry only `drugId`; the repo resolves `drug` through this.
    private val fakeDrugs = mutableMapOf<String, Drug>()
    private val drugRepo = object : DrugRepository {
        override suspend fun catalog(): List<Drug> = fakeDrugs.values.toList()
        override suspend fun get(drugId: String): Drug =
            fakeDrugs[drugId] ?: throw NoSuchElementException(drugId)
        override fun lookupStream(query: String): Flow<DrugLookupEvent> = emptyFlow()
    }
    private var drains = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(MedsTestMoshi.instance))
            .build()
            .create(MedicationsApi::class.java)

        dao = FakeMedicationDao()
        adherenceDao = FakeMedicationAdherenceDao(dao.mirror)
        outboxDao = FakeOutboxDao()
        val outbox = OutboxRepository(
            outboxDao = outboxDao,
            mirror = dao.mirror,
            replay = io.mockk.mockk(relaxed = true),
            deviceIdProvider = fakeDeviceIdProvider("device-A"),
            io = Dispatchers.Unconfined,
            clock = { 1_000L },
        )
        val support = MirrorRepositorySupport(
            mirror = dao.mirror,
            outbox = outbox,
            killSwitch = KillSwitchGate { false },
            drainTrigger = DrainTrigger { drains++ },
        )
        repository = DefaultMedicationRepository(api, drugRepo, dao, adherenceDao, support, MedsTestMoshi.instance, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val activeMedJson = """
        {"medicationId":"m1","drugId":null,"drug":null,"customName":"X","status":"ACTIVE",
         "dose":250.0,"unit":"mg",
         "frequency":{"type":"DAILY","timesPerPeriod":1},
         "timeSlots":[],"startDate":"2026-01-01","endDate":null,"correlatedMarkers":[]}
    """.trimIndent()

    private val discontinuedMedJson = """
        {"medicationId":"m2","drugId":"d1",
         "drug":{"drugId":"d1","name":"Testosterone Cypionate","aliases":["Test Cyp"],
                 "category":"PRESCRIPTION","form":"INJECTABLE_VIAL","defaultUnit":"mg",
                 "commonDoses":["100","200"],"imageUrl":null,"imageFallback":null,
                 "suggestedMarkers":["TESTOSTERONE_TOTAL"],"description":null},
         "customName":null,"status":"DISCONTINUED","dose":200.0,"unit":"mg",
         "frequency":{"type":"WEEKLY","timesPerPeriod":1,"specificDays":["MON"]},
         "timeSlots":[{"window":"MORNING","dose":200.0}],
         "protocolId":null,"notes":null,"prescribedBy":null,
         "startDate":"2026-01-01","endDate":"2026-03-01",
         "discontinueReason":"SWITCHED","discontinueNotes":"moved to enanthate",
         "correlatedMarkers":[],
         "dosagePeriods":[{"dose":200.0,"unit":"mg","startDate":"2026-01-01","endDate":"2026-03-01"}],
         "adherence":{"last30Days":[{"date":"2026-05-01","taken":false}],"percentage":0.0}}
    """.trimIndent()

    @Test
    fun `list fills the mirror and filters by status`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[$activeMedJson]"))
        val meds = repository.list(MedicationStatus.ACTIVE)
        assertEquals(1, meds.size)
        assertEquals("m1", meds.first().medicationId)
        // The mirror fill calls GET with no status param; filtering is client-side.
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/api/me/medications"))
    }

    // A synced (mirror) row carries only drugId — `drug` is null, as Firestore
    // stores it. Without resolution this renders "Unknown" + a broken image.
    private val syncedMedJson = """
        {"medicationId":"m9","drugId":"d9","drug":null,"customName":null,"status":"ACTIVE",
         "dose":5.0,"unit":"mg",
         "frequency":{"type":"DAILY","timesPerPeriod":1},
         "timeSlots":[],"startDate":"2026-01-01","endDate":null,"correlatedMarkers":[]}
    """.trimIndent()

    @Test
    fun `list resolves the drug for a mirror row that only has drugId`() = runBlocking {
        fakeDrugs["d9"] = Drug(
            drugId = "d9",
            name = "Tadalafil",
            category = DrugCategory.PRESCRIPTION,
            form = DrugForm.INJECTABLE_VIAL,
            defaultUnit = "mg",
            imageUrl = "https://img/tadalafil.png",
            imageFallback = null,
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[$syncedMedJson]"))
        val med = repository.list(MedicationStatus.ACTIVE).single()
        assertEquals("Tadalafil", med.displayName)
        assertEquals("https://img/tadalafil.png", med.drug?.imageUrl)
    }

    @Test
    fun `list with no status parses active and discontinued from the mirror`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[$activeMedJson,$discontinuedMedJson]"),
        )
        val meds = repository.list()
        assertEquals(2, meds.size)
        assertEquals(1, meds.count { it.status == MedicationStatus.ACTIVE })
        assertEquals(1, meds.count { it.status == MedicationStatus.DISCONTINUED })
    }

    @Test
    fun `list parses a fully-populated discontinued medication`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[$discontinuedMedJson]"),
        )
        val med = repository.list().single()
        assertEquals(MedicationStatus.DISCONTINUED, med.status)
        assertEquals(DiscontinueReason.SWITCHED, med.discontinueReason)
        assertEquals(LocalDate.of(2026, 3, 1), med.endDate)
        assertEquals(1, med.dosagePeriods.size)
        assertEquals(LocalDate.of(2026, 3, 1), med.dosagePeriods.first().endDate)
    }

    @Test
    fun `offline create shows up instantly as PENDING and enqueues a CREATE mutation`() = runBlocking {
        val created = repository.create(
            CreateMedicationRequest(
                drugId = null,
                customName = "Creatine",
                dose = 5.0,
                unit = "g",
                frequency = FrequencyConfig(type = FrequencyType.DAILY, timesPerPeriod = 1),
                timeSlots = emptyList(),
                correlatedMarkers = emptyList(),
            ),
        )
        assertEquals(0, server.requestCount)
        val row = dao.mirror.getRow(MirrorTables.MEDICATIONS, created.medicationId)!!
        assertTrue(row.dirty)
        assertEquals("PENDING", row.syncState)
        val queued = outboxDao.listByEntity(created.medicationId).single()
        assertEquals(OutboxOp.CREATE.name, queued.op)
        assertTrue(drains >= 1)
    }

    @Test
    fun `get parses the flat detail payload with change history`() = runBlocking {
        val detailJson = """
            {"medicationId":"m1","drugId":null,"drug":null,"customName":"X","status":"DISCONTINUED",
             "dose":250.0,"unit":"mg",
             "frequency":{"type":"DAILY","timesPerPeriod":1},
             "timeSlots":[],"startDate":"2026-01-01","endDate":"2026-04-01",
             "discontinueReason":"COMPLETED","discontinueNotes":"course done",
             "correlatedMarkers":[],
             "dosagePeriods":[{"dose":250.0,"unit":"mg","startDate":"2026-01-01","endDate":"2026-04-01"}],
             "history":[{"historyId":"h1","changeType":"DOSE_CHANGE","previousValue":"200 mg",
                         "newValue":"250 mg","changedAt":"2026-02-01T10:00:00Z","notes":"titration"}]}
        """.trimIndent()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(detailJson),
        )
        val detail = repository.get("m1")
        assertEquals("m1", detail.medication.medicationId)
        assertEquals(MedicationStatus.DISCONTINUED, detail.medication.status)
        assertEquals(250.0, detail.medication.dose, 0.0)
        assertEquals(1, detail.history.size)
        assertEquals("250 mg", detail.history.first().newValue)
    }

    @Test
    fun `changeDose posts to dosage endpoint with body`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(activeMedJson))
        repository.changeDose(
            "m1",
            ChangeDoseRequest(dose = 250.0, unit = "mg", startDate = LocalDate.of(2026, 5, 30), changeNotes = "labs"),
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/me/medications/m1/dosage"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"dose\":250.0"))
        assertTrue(body.contains("2026-05-30"))
    }

    @Test
    fun `reactivate posts to reactivate endpoint with resume date`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(activeMedJson))
        repository.reactivate("m1", LocalDate.of(2026, 6, 1))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/me/medications/m1/reactivate"))
        assertTrue(request.body.readUtf8().contains("2026-06-01"))
    }

    @Test
    fun `discontinue posts reason notes and endDate`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(activeMedJson))
        repository.discontinue("m1", DiscontinueReason.SWITCHED, "moved", LocalDate.of(2026, 4, 1))
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/api/me/medications/m1/discontinue"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"reason\":\"SWITCHED\""))
        assertTrue(body.contains("2026-04-01"))
    }

    @Test
    fun `todaysDoses overlays an offline adherence log so it shows immediately as taken`() = runBlocking {
        // The live `today` projection shows the dose NOT taken yet.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"medicationId":"m1","drugName":"X","window":"MORNING","dose":250.0,"unit":"mg","taken":false}]""",
            ),
        )
        // Seed an offline adherence log for today via the same mirror store.
        val today = java.time.LocalDate.now()
        val id = "m1/$today/MORNING"
        val payload = MedsTestMoshi.instance.adapter(AdherenceMirrorPayload::class.java).toJson(
            AdherenceMirrorPayload(
                medicationId = "m1",
                date = today,
                window = "MORNING",
                taken = true,
                takenAt = java.time.Instant.parse("2026-05-30T08:00:00Z"),
                dose = 250.0,
            ),
        )
        dao.mirror.upsert(
            MirrorTables.MEDICATION_ADHERENCE,
            MirrorRowData(id, payload, 1L, "ACTIVE", true, "PENDING"),
        )

        val doses = repository.todaysDoses()
        assertEquals(1, doses.size)
        assertTrue("offline log overlays the live checklist as taken", doses.single().taken)
    }

    @Test
    fun `delete tombstones the row and enqueues a DELETE mutation`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[$activeMedJson]"))
        repository.list() // fill the mirror with m1 (one request)
        val before = server.requestCount
        repository.delete("m1")
        assertEquals(before, server.requestCount) // delete itself makes no network call
        val row = dao.mirror.getRow(MirrorTables.MEDICATIONS, "m1")!!
        assertEquals("ARCHIVED", row.status)
        val queued = outboxDao.listByEntity("m1").single()
        assertEquals(OutboxOp.DELETE.name, queued.op)
    }
}

/**
 * In-memory [MedicationDao] over a shared [FakeMirrorOps] (exposed as [mirror]) so
 * the support's optimistic writes and this DAO's read Flow observe the SAME store.
 */
private class FakeMedicationDao : MedicationDao {
    private val flow = MutableStateFlow<List<MedicationEntity>>(emptyList())
    val mirror = TrackingMirrorOps { publish() }

    private fun publish() {
        flow.value = mirror.rows.entries
            .filter { it.key.startsWith("${MirrorTables.MEDICATIONS}:") }
            .map { it.value }
            .filter { it.status != "ARCHIVED" }
            .map { MedicationEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }
            .sortedByDescending { it.lastUpdate }
    }

    override fun observeActive(): Flow<List<MedicationEntity>> {
        publish()
        return flow
    }

    override suspend fun getById(id: String): MedicationEntity? =
        mirror.getRow(MirrorTables.MEDICATIONS, id)
            ?.let { MedicationEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }

    override suspend fun upsert(row: MedicationEntity) =
        mirror.upsert(
            MirrorTables.MEDICATIONS,
            MirrorRowData(row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState),
        )

    override suspend fun upsertAll(rows: List<MedicationEntity>) { rows.forEach { upsert(it) } }
    override suspend fun markArchived(id: String, lastUpdate: Long) =
        mirror.markArchived(MirrorTables.MEDICATIONS, id, lastUpdate)
    override suspend fun delete(id: String) = mirror.delete(MirrorTables.MEDICATIONS, id)
}

/**
 * In-memory [MedicationAdherenceDao] reading the SAME [FakeMirrorOps] store the
 * support writes to, so an optimistic adherence log surfaces in the overlay.
 */
private class FakeMedicationAdherenceDao(private val mirror: FakeMirrorOps) : MedicationAdherenceDao {
    private fun all() = mirror.rows.entries
        .filter { it.key.startsWith("${MirrorTables.MEDICATION_ADHERENCE}:") }
        .map { it.value }
        .map { MedicationAdherenceEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }
        .sortedByDescending { it.lastUpdate }

    override fun observeActive(): Flow<List<MedicationAdherenceEntity>> =
        MutableStateFlow(all().filter { it.status != "ARCHIVED" })
    override fun observeAll(): Flow<List<MedicationAdherenceEntity>> = MutableStateFlow(all())
    override suspend fun getById(id: String): MedicationAdherenceEntity? = all().firstOrNull { it.id == id }
    override suspend fun upsert(row: MedicationAdherenceEntity) =
        mirror.upsert(
            MirrorTables.MEDICATION_ADHERENCE,
            MirrorRowData(row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState),
        )
    override suspend fun upsertAll(rows: List<MedicationAdherenceEntity>) { rows.forEach { upsert(it) } }
    override suspend fun markArchived(id: String, lastUpdate: Long) =
        mirror.markArchived(MirrorTables.MEDICATION_ADHERENCE, id, lastUpdate)
    override suspend fun delete(id: String) = mirror.delete(MirrorTables.MEDICATION_ADHERENCE, id)
}

/** [FakeMirrorOps] that fires [onChange] after each mutating op. */
private class TrackingMirrorOps(private val onChange: () -> Unit) : FakeMirrorOps() {
    override suspend fun upsert(table: String, row: MirrorRowData) {
        super.upsert(table, row); onChange()
    }
    override suspend fun markArchived(table: String, id: String, lastUpdate: Long) {
        super.markArchived(table, id, lastUpdate); onChange()
    }
    override suspend fun delete(table: String, id: String) { super.delete(table, id); onChange() }
}
