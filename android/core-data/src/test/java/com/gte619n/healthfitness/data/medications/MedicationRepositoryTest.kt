package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import kotlinx.coroutines.Dispatchers
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

class MedicationRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultMedicationRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(MedsTestMoshi.instance))
            .build()
            .create(MedicationsApi::class.java)
        repository = DefaultMedicationRepository(api, Dispatchers.Unconfined)
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

    // A fully-populated discontinued medication exactly as the backend
    // `MedicationResponse` serialises it: closed dosage periods (no open period),
    // a populated `adherence` summary, weekly `specificDays`, and the
    // `discontinueReason`/`endDate` fields that only discontinued meds carry.
    // The minimal [activeMedJson] never exercises these through Moshi, so this
    // guards the History tab's data path.
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
    fun `list maps status query`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[$activeMedJson]"))
        val meds = repository.list(MedicationStatus.ACTIVE)
        assertEquals(1, meds.size)
        assertEquals("m1", meds.first().medicationId)
        assertTrue(server.takeRequest().path!!.contains("status=ACTIVE"))
    }

    @Test
    fun `list with no status omits the query param and parses active and discontinued`() = runBlocking {
        // Mirrors the production MedicationsViewModel call: no status filter, so
        // the backend returns every medication and the client partitions them.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[$activeMedJson,$discontinuedMedJson]"),
        )
        val meds = repository.list()
        assertEquals(2, meds.size)
        assertEquals(1, meds.count { it.status == MedicationStatus.ACTIVE })
        assertEquals(1, meds.count { it.status == MedicationStatus.DISCONTINUED })

        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/api/me/medications"))
        assertTrue("status query must be omitted", !request.path!!.contains("status"))
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
    fun `get parses the flat detail payload with change history`() = runBlocking {
        // The backend MedicationDetailResponse is flat: the medication fields sit
        // at the top level alongside `history` (it is NOT nested under a
        // `medication` key). Parsing the wrong shape made the detail/history view
        // fail to load.
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
        // [PR#8]
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
        // [PR#8]
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(activeMedJson))
        repository.reactivate("m1", LocalDate.of(2026, 6, 1))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/me/medications/m1/reactivate"))
        assertTrue(request.body.readUtf8().contains("2026-06-01"))
    }

    @Test
    fun `discontinue posts reason notes and endDate`() = runBlocking {
        // [PR#8]
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(activeMedJson))
        repository.discontinue("m1", DiscontinueReason.SWITCHED, "moved", LocalDate.of(2026, 4, 1))
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/api/me/medications/m1/discontinue"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"reason\":\"SWITCHED\""))
        assertTrue(body.contains("2026-04-01"))
    }

    @Test
    fun `delete issues DELETE`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        repository.delete("m1")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertTrue(request.path!!.endsWith("/api/me/medications/m1"))
    }
}
