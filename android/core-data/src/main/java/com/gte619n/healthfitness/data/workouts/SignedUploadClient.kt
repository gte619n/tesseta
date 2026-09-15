package com.gte619n.healthfitness.data.workouts

import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * IMPL-GYM-003: PUTs bytes to a signed GCS upload URL.
 *
 * Deliberately uses its OWN bare [OkHttpClient] — NOT the app's shared client —
 * because the shared client's auth interceptor adds `Authorization: Bearer …` to
 * every request, and GCS rejects a signed-URL request that also carries an auth
 * header (the same trap as authenticated image loads; see CLAUDE.md). The URL's
 * signature is the only credential needed.
 *
 * The body streams from the source [InputStream] with a known content length, so
 * a large video is never fully buffered in memory (no OOM) and the upload isn't
 * chunked (GCS signed PUTs expect a fixed length).
 */
@Singleton
class SignedUploadClient @Inject constructor() {

    private val client = OkHttpClient()

    suspend fun put(
        url: String,
        method: String,
        mimeType: String,
        contentLength: Long,
        headers: Map<String, String>,
        openStream: () -> InputStream,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = object : RequestBody() {
                override fun contentType() = mimeType.toMediaTypeOrNull()
                override fun contentLength() = contentLength
                override fun writeTo(sink: BufferedSink) {
                    openStream().use { input -> sink.writeAll(input.source()) }
                }
            }
            val builder = Request.Builder().url(url).method(method, body)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Video upload failed: HTTP ${response.code}")
                }
            }
        }
    }
}
