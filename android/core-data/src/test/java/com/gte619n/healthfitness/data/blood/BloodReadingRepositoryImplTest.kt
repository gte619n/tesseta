package com.gte619n.healthfitness.data.blood

import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.first
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

class BloodReadingRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BloodApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder()
            .add(LocalDateAdapter())
            .add(InstantAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BloodApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

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
    fun refreshPopulatesState() = runTest {
        server.enqueue(MockResponse().setBody("[${readingJson("r1", 82.0)}]"))
        val repo = BloodReadingRepositoryImpl(api)
        repo.refresh()
        val readings = repo.observeReadings().first()
        assertEquals(1, readings.size)
        assertEquals(BloodMarker.LDL, readings.first().marker)
        assertEquals(82.0, readings.first().value, 0.0001)
    }

    @Test
    fun createPostsExpectedBodyAndUpdatesState() = runTest {
        server.enqueue(MockResponse().setBody(readingJson("r1", 82.0)))
        val repo = BloodReadingRepositoryImpl(api)
        repo.create(
            marker = BloodMarker.LDL,
            value = 82.0,
            unit = null,
            sampleDate = LocalDate.of(2026, 5, 1),
            labSource = null,
            notes = null,
        )
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/me/blood", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"marker\":\"LDL\""))
        assertTrue(body.contains("\"value\":82.0"))
        assertEquals(1, repo.observeReadings().first().size)
    }

    @Test
    fun deleteRemovesFromState() = runTest {
        server.enqueue(MockResponse().setBody("[${readingJson("r1", 82.0)}]"))
        server.enqueue(MockResponse().setResponseCode(204))
        val repo = BloodReadingRepositoryImpl(api)
        repo.refresh()
        assertEquals(1, repo.observeReadings().first().size)
        repo.delete("r1")
        assertTrue(repo.observeReadings().first().isEmpty())
    }
}
