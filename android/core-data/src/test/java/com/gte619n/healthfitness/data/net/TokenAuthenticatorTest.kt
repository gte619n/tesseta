package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
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

class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: GoogleAuthRepository
    private lateinit var cache: IdTokenCache

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = mockk(relaxed = true)
        cache = mockk(relaxed = true)
        coEvery { cache.write(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun signedIn(token: String): AuthState.SignedIn =
        AuthState.SignedIn(
            userId = "u1",
            email = "u1@example.com",
            displayName = "User One",
            idToken = token,
        )

    private fun clientWith(authenticator: TokenAuthenticator): OkHttpClient =
        OkHttpClient.Builder()
            .authenticator(authenticator)
            .build()

    @Test
    fun `401 then refresh returns token retries with new bearer and retry tag`() = runTest {
        coEvery { repo.silentRefresh() } returns signedIn("new-token")

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val authenticator = TokenAuthenticator(repo, cache)
        val client = clientWith(authenticator)

        val request = Request.Builder()
            .url(server.url("/data"))
            .header("Authorization", "Bearer stale-token")
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)
        response.close()

        // First request: original stale bearer, no retry tag.
        val first = server.takeRequest()
        assertEquals("Bearer stale-token", first.getHeader("Authorization"))
        assertNull(first.getHeader("X-HF-Auth-Retry"))

        // Second (retried) request: new bearer + retry tag.
        val second = server.takeRequest()
        assertEquals("Bearer new-token", second.getHeader("Authorization"))
        assertEquals("1", second.getHeader("X-HF-Auth-Retry"))

        coVerify(exactly = 1) { repo.silentRefresh() }
        coVerify(exactly = 1) { cache.write("new-token", 0L) }
    }

    @Test
    fun `401 then refresh returns null does not retry`() = runTest {
        coEvery { repo.silentRefresh() } returns AuthState.SignedOut

        server.enqueue(MockResponse().setResponseCode(401))

        val authenticator = TokenAuthenticator(repo, cache)
        val client = clientWith(authenticator)

        val request = Request.Builder()
            .url(server.url("/data"))
            .header("Authorization", "Bearer stale-token")
            .build()

        val response = client.newCall(request).execute()
        assertEquals(401, response.code)
        response.close()

        // Only the original request should have been made.
        assertEquals(1, server.requestCount)
        coVerify(exactly = 1) { repo.silentRefresh() }
    }

    @Test
    fun `request already tagged returns null without refreshing`() {
        val authenticator = TokenAuthenticator(repo, cache)

        val taggedRequest = Request.Builder()
            .url(server.url("/data"))
            .header("Authorization", "Bearer some-token")
            .header("X-HF-Auth-Retry", "1")
            .build()

        val priorResponse = okhttp3.Response.Builder()
            .request(taggedRequest)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val result = authenticator.authenticate(null, priorResponse)

        assertNull(result)
        coVerify(exactly = 0) { repo.silentRefresh() }
    }
}
