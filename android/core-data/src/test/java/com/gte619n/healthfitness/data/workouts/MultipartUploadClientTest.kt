package com.gte619n.healthfitness.data.workouts

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultipartUploadClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MultipartUploadClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MultipartUploadClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `builds multipart body with expected disposition and field name`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"coverPhotoUrl":"http://x/y.webp"}"""))

        val url = server.url("/api/me/gyms/g1/photo").toString()
        val response = client.upload(
            url = url,
            fileFieldName = "file",
            fileName = "photo.jpg",
            mediaType = "image/jpeg",
            bytes = "hello".toByteArray(),
        )

        assertTrue(response.contains("coverPhotoUrl"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val contentType = recorded.getHeader("Content-Type") ?: ""
        assertTrue("expected multipart, was $contentType", contentType.startsWith("multipart/form-data"))

        val body = recorded.body.readUtf8()
        assertTrue(body.contains("Content-Disposition: form-data; name=\"file\""))
        assertTrue(body.contains("filename=\"photo.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains("hello"))
    }

    @Test(expected = java.io.IOException::class)
    fun `non-2xx throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val url = server.url("/api/me/gyms/g1/photo").toString()
        client.upload(
            url = url,
            fileFieldName = "file",
            fileName = "photo.jpg",
            mediaType = "image/jpeg",
            bytes = "x".toByteArray(),
        )
    }
}
