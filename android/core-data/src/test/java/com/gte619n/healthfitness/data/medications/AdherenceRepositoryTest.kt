package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.domain.medications.TimeWindow
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
import java.time.Instant
import java.time.LocalDate

class AdherenceRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultAdherenceRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(MedsTestMoshi.instance))
            .build()
            .create(AdherenceApi::class.java)
        repository = DefaultAdherenceRepository(api, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `logDose posts window and takenAt`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val takenAt = Instant.parse("2026-05-30T08:00:00Z")
        repository.logDose("m1", TimeWindow.MORNING, takenAt = takenAt, dose = 200.0)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/me/medications/m1/adherence"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"window\":\"MORNING\""))
        assertTrue(body.contains("2026-05-30T08:00:00Z"))
    }

    @Test
    fun `undoDose builds path with iso date and window`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        repository.undoDose("m1", LocalDate.of(2026, 5, 30), TimeWindow.EVENING)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertTrue(request.path!!.endsWith("/api/me/medications/m1/adherence/2026-05-30/EVENING"))
    }
}
