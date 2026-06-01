package com.gte619n.healthfitness.data.medications

import app.cash.turbine.test
import com.gte619n.healthfitness.data.net.SseClient
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DrugLookupStreamClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DrugLookupStreamClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val sse = SseClient(OkHttpClient(), server.url("/").toString())
        client = DrugLookupStreamClient(sse, MedsTestMoshi.instance)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `emits progress then found and completes`() = runTest {
        val body = buildString {
            append("data: {\"phase\":\"searching\",\"message\":\"Searching…\"}\n\n")
            append("data: {\"phase\":\"generating_image\",\"message\":\"Generating image…\"}\n\n")
            append(
                "data: {\"phase\":\"complete\",\"drug\":{\"drugId\":\"d1\",\"name\":\"Testosterone Cypionate\"," +
                    "\"category\":\"PRESCRIPTION\",\"form\":\"INJECTABLE_VIAL\",\"defaultUnit\":\"mg\"," +
                    "\"imageUrl\":null,\"imageFallback\":null}}\n\n",
            )
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        client.stream("testosterone").test {
            val first = awaitItem()
            assertTrue(first is DrugLookupEvent.Progress)
            assertEquals("searching", (first as DrugLookupEvent.Progress).phase)

            val second = awaitItem()
            assertEquals("generating_image", (second as DrugLookupEvent.Progress).phase)

            val third = awaitItem()
            assertTrue(third is DrugLookupEvent.Found)
            assertEquals("Testosterone Cypionate", (third as DrugLookupEvent.Found).drug.name)

            awaitComplete()
        }
    }

    @Test
    fun `emits not found and completes`() = runTest {
        val body = "data: {\"phase\":\"not_found\",\"message\":\"No match\"}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        client.stream("zzzz").test {
            val event = awaitItem()
            assertTrue(event is DrugLookupEvent.NotFound)
            assertEquals("No match", (event as DrugLookupEvent.NotFound).message)
            awaitComplete()
        }
    }

    @Test
    fun `posts query body to lookup stream path`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"phase\":\"not_found\"}\n\n"),
        )
        client.stream("aspirin").test {
            awaitItem()
            awaitComplete()
        }
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/api/drugs/lookup/stream"))
        assertTrue(request.body.readUtf8().contains("aspirin"))
    }
}
