package com.gte619n.healthfitness.mobile.push

import com.gte619n.healthfitness.data.sync.DeviceIdProvider
import com.gte619n.healthfitness.data.sync.SyncApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.mockk
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

/**
 * IMPL-AND-20 (Phase 6) — TokenRegistration wire-contract tests (D18).
 *
 * Drives a real Retrofit [SyncApi] against MockWebServer with a faked
 * [DeviceIdProvider], asserting the register PUT and deregister DELETE hit
 * `/api/me/devices/fcm` with the right token + deviceId. The Firebase-dependent
 * `register()` (which fetches the token from `FirebaseMessaging`) is not exercised
 * here — that needs a device; `registerToken(token)` covers the wire path.
 */
class TokenRegistrationTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SyncApi
    private val deviceIdProvider = mockk<DeviceIdProvider>()

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SyncApi::class.java)
        coEvery { deviceIdProvider.deviceId() } returns "device-abc"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reg() = TokenRegistration(api, deviceIdProvider)

    @Test
    fun `registerToken PUTs the token and deviceId to the fcm endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        reg().registerToken("fcm-token-123")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/me/devices/fcm", req.path)
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"token\":\"fcm-token-123\""))
        assertTrue(body, body.contains("\"deviceId\":\"device-abc\""))
    }

    @Test
    fun `unregister DELETEs with the deviceId`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        reg().unregister()

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/me/devices/fcm", req.path)
        assertTrue(req.body.readUtf8().contains("\"deviceId\":\"device-abc\""))
    }

    @Test
    fun `registerToken swallows a server error so sign-in is never blocked`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        // Should not throw.
        reg().registerToken("fcm-token-123")
    }

    @Test
    fun `unregister swallows a server error so sign-out is never blocked`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        // Should not throw.
        reg().unregister()
    }
}
