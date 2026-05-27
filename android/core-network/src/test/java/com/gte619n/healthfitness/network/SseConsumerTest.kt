package com.gte619n.healthfitness.network

import app.cash.turbine.test
import com.gte619n.healthfitness.network.sse.SseConsumer
import com.gte619n.healthfitness.network.sse.SseEvent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SseConsumerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `emits open then data events then closes`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    buildString {
                        append("data: hello\n\n")
                        append("event: ping\n")
                        append("data: world\n\n")
                    },
                ),
        )

        val client = OkHttpClient.Builder().build()
        val consumer = SseConsumer(EventSources.createFactory(client))
        val request = Request.Builder().url(server.url("/events")).build()

        consumer.stream(request).test(timeout = 5.seconds) {
            assertEquals(SseEvent.Open, awaitItem())
            val first = awaitItem() as SseEvent.Data
            assertEquals(null, first.name)
            assertEquals("hello", first.payload)
            val second = awaitItem() as SseEvent.Data
            assertEquals("ping", second.name)
            assertEquals("world", second.payload)

            // MockWebServer closes the body once it's drained; we accept either
            // a `Closed` terminal or a `Failure` (some OkHttp versions surface
            // the EOF as a failure). Either way the flow must terminate.
            val terminal = awaitItem()
            assertTrue(
                "expected a terminal SseEvent, got $terminal",
                terminal is SseEvent.Closed || terminal is SseEvent.Failure,
            )
            cancelAndConsumeRemainingEvents()
        }
    }
}
