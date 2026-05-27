package com.gte619n.healthfitness.data.profile

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ProfileRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ProfileRepositoryImpl

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
        repo = ProfileRepositoryImpl(retrofit.create(ProfileService::class.java), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get parses dto into domain Profile`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"userId":"u-1","email":"a@b","displayName":"Alice","heightCm":180}
                """.trimIndent(),
            ),
        )
        val profile = repo.get().getOrThrow()
        assertEquals("u-1", profile.userId)
        assertEquals("a@b", profile.email)
        assertEquals("Alice", profile.displayName)
        assertEquals(180, profile.heightCm)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/me", req.path)
    }

    @Test
    fun `get tolerates nullable email and heightCm`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"userId":"u-2","email":null,"displayName":null,"heightCm":null}
                """.trimIndent(),
            ),
        )
        val profile = repo.get().getOrThrow()
        assertEquals("u-2", profile.userId)
        assertNull(profile.email)
        assertNull(profile.displayName)
        assertNull(profile.heightCm)
    }

    @Test
    fun `patch sends heightCm and returns updated profile`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"userId":"u-1","email":"a@b","displayName":"Alice","heightCm":188}
                """.trimIndent(),
            ),
        )
        val updated = repo.updateHeightCm(188).getOrThrow()
        assertEquals(188, updated.heightCm)

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"heightCm\":188"))
    }

    @Test
    fun `patch with null heightCm omits the field per default Moshi semantics`() = runBlocking {
        // The reflective KotlinJsonAdapterFactory omits null fields
        // unless `serializeNulls()` is set on the Moshi builder. We
        // accept that behavior here because the only writeable field on
        // the patch surface today is `heightCm`, and the spec's "clear
        // my height" flow isn't exposed in the UI (the Save button is
        // disabled when both inputs are blank). If we ever surface a
        // "Remove height" action, switch Moshi to serializeNulls or
        // expose the field as a sealed Update<Int> type.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"userId":"u-1","email":"a@b","displayName":"Alice","heightCm":null}
                """.trimIndent(),
            ),
        )
        val updated = repo.updateHeightCm(null).getOrThrow()
        assertNull(updated.heightCm)
        val body = server.takeRequest().body.readUtf8()
        // Either `{"heightCm":null}` (with serializeNulls) or `{}`.
        assertTrue(
            "Expected empty or null-heightCm body, got: $body",
            body == "{}" || body.contains("\"heightCm\":null"),
        )
    }

    @Test
    fun `get returns failure when server errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repo.get()
        assertTrue(result.isFailure)
    }
}
