package com.gte619n.healthfitness.data.googlehealth

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class GoogleHealthRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: GoogleHealthRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        repo = GoogleHealthRepositoryImpl(
            retrofit.create(GoogleHealthService::class.java),
            Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `status parses connected payload with iso timestamp`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"connected":true,"connectedAt":"2026-05-20T10:30:00Z"}""",
            ),
        )
        val s = repo.status().getOrThrow()
        assertTrue(s.connected)
        assertNotNull(s.connectedAtEpochSeconds)
        val expected = java.time.Instant.parse("2026-05-20T10:30:00Z").epochSecond
        assertEquals(expected, s.connectedAtEpochSeconds)
        assertEquals("/api/me/google-health/status", server.takeRequest().path)
    }

    @Test
    fun `status handles disconnected payload`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"connected":false,"connectedAt":null}""",
            ),
        )
        val s = repo.status().getOrThrow()
        assertFalse(s.connected)
        assertNull(s.connectedAtEpochSeconds)
    }

    @Test
    fun `connect sends serverAuthCode body and returns success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = repo.connectWithServerAuthCode("code-A")
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/me/google-health/connect", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"serverAuthCode\":\"code-A\""))
    }

    @Test
    fun `disconnect issues DELETE`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = repo.disconnect()
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/me/google-health/connect", req.path)
    }

    @Test
    fun `status returns failure on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repo.status().isFailure)
    }
}
