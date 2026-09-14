package com.gte619n.healthfitness.data.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * SEC-012: the image loader must send the bearer to the backend photo endpoint
 * but NEVER to the GCS signed URL it 302-redirects to (GCS rejects a signed
 * request that also carries an Authorization header), and must leave unrelated
 * (public) image hosts untouched.
 */
class ImageAuthInterceptorTest {

    private lateinit var backend: MockWebServer
    private lateinit var storage: MockWebServer

    @Before
    fun setUp() {
        backend = MockWebServer().apply { start() }
        storage = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        backend.shutdown()
        storage.shutdown()
    }

    private fun client(host: String, token: String?): OkHttpClient =
        OkHttpClient.Builder()
            .addNetworkInterceptor(ImageAuthInterceptor(host) { token })
            .build()

    @Test
    fun sendsBearerToBackendButNotToTheSignedUrlRedirect() = runTest {
        // Model prod's distinct hosts (api.* vs storage.googleapis.com) with two
        // loopback aliases: reach the backend via "localhost" and have it redirect
        // to "127.0.0.1" so the two hops carry different url.host values.
        val backendUrl = backend.url("/api/me/nutrition/photo/e1?date=2026-09-14")
            .newBuilder().host("localhost").build()
        val signed = storage.url("/read?sig=abc").newBuilder().host("127.0.0.1").build()
        backend.enqueue(MockResponse().setResponseCode(302).setHeader("Location", signed.toString()))
        storage.enqueue(MockResponse().setResponseCode(200).setBody("img-bytes"))

        client("localhost", "tok-123")
            .newCall(Request.Builder().url(backendUrl).build())
            .execute()
            .use { assertEquals(200, it.code) }

        // Backend hop carried the bearer...
        val toBackend = backend.takeRequest()
        assertEquals("Bearer tok-123", toBackend.getHeader("Authorization"))
        // ...but the redirect hop to the (different-host) signed URL did NOT.
        val toStorage = storage.takeRequest()
        assertNull(toStorage.getHeader("Authorization"))
    }

    @Test
    fun leavesNonBackendHostsUntouched() = runTest {
        storage.enqueue(MockResponse().setResponseCode(200).setBody("img"))
        // Backend host is set to something other than the server we call.
        client("api.example.com", "tok-123")
            .newCall(Request.Builder().url(storage.url("/public/drug.png")).build())
            .execute()
            .use { assertEquals(200, it.code) }

        assertNull(storage.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun noHeaderWhenTokenMissing() = runTest {
        backend.enqueue(MockResponse().setResponseCode(200).setBody("img"))
        client(backend.hostName, null)
            .newCall(Request.Builder().url(backend.url("/api/me/nutrition/photo/e1")).build())
            .execute()
            .use { assertEquals(200, it.code) }

        assertNull(backend.takeRequest().getHeader("Authorization"))
    }
}
