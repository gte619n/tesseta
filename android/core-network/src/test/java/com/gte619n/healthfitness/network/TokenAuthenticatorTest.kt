package com.gte619n.healthfitness.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TokenAuthenticatorTest {

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
    fun `401 triggers refresh and retries with new bearer`() {
        val provider = StubTokenProvider(
            current = "old-token",
            refreshResults = listOf("new-token"),
        )
        val client = clientWith(provider)

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/protected")).build()).execute()
        response.use {
            assertEquals(200, it.code)
        }

        assertEquals(1, provider.refreshCount)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer old-token", first.getHeader("Authorization"))
        assertEquals("Bearer new-token", second.getHeader("Authorization"))
        assertEquals("1", second.getHeader("X-HF-Auth-Retry"))
    }

    @Test
    fun `401 with failed refresh surfaces original 401`() {
        val provider = StubTokenProvider(
            current = "old-token",
            refreshResults = listOf(null),
        )
        val client = clientWith(provider)

        server.enqueue(MockResponse().setResponseCode(401))

        val response = client.newCall(Request.Builder().url(server.url("/protected")).build()).execute()
        response.use {
            assertEquals(401, it.code)
        }

        assertEquals(1, provider.refreshCount)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `repeated 401 does not loop`() {
        // refresh keeps handing out new tokens, but the server stays 401 —
        // the retry header tag must short-circuit a second authenticator pass.
        val provider = StubTokenProvider(
            current = "old-token",
            refreshResults = listOf("new-token", "newer-token"),
        )
        val client = clientWith(provider)

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val response = client.newCall(Request.Builder().url(server.url("/protected")).build()).execute()
        response.use {
            assertEquals(401, it.code)
        }

        assertEquals(1, provider.refreshCount)
        assertEquals(2, server.requestCount)

        // Drain the recorded requests so we can assert the retry header.
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("1", second.getHeader("X-HF-Auth-Retry"))
        assertNull("no third request should fire", server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    private fun clientWith(provider: StubTokenProvider): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider))
            .authenticator(TokenAuthenticator(provider))
            .build()
}
