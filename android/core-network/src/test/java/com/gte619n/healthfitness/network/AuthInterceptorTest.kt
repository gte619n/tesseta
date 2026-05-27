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

class AuthInterceptorTest {

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
    fun `attaches bearer header when token present`() {
        val client = clientWith(StubTokenProvider(current = "abc.def.ghi"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/whatever")).build()).execute().use { /* discard */ }

        val recorded = server.takeRequest()
        assertEquals("Bearer abc.def.ghi", recorded.getHeader("Authorization"))
    }

    @Test
    fun `null token leaves authorization unset`() {
        val client = clientWith(StubTokenProvider(current = null))
        server.enqueue(MockResponse().setResponseCode(401))

        client.newCall(Request.Builder().url(server.url("/whatever")).build()).execute().use { /* discard */ }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `whitespace token treated as missing`() {
        val client = clientWith(StubTokenProvider(current = "   "))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/whatever")).build()).execute().use { /* discard */ }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    private fun clientWith(provider: StubTokenProvider): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider))
            .build()
}
