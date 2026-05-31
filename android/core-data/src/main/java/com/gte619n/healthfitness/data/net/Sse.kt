package com.gte619n.healthfitness.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One decoded Server-Sent Event. */
data class SseEvent(val event: String?, val data: String)

/**
 * Generic POST + `text/event-stream` consumer (IMPL-AND-00). Used by the drug
 * AI lookup (IMPL-AND-03) and any other POST-body SSE endpoint. Reuses the
 * shared [OkHttpClient] so the bearer token is attached by [AuthInterceptor].
 *
 * Cancelling the collector cancels the read loop. The flow runs on IO.
 */
@Singleton
class SseClient @Inject constructor(
    client: OkHttpClient,
    @BackendBaseUrl private val baseUrl: String,
) {
    // SSE responses are long-lived: the backend holds the connection open while
    // it works (drug AI lookup + image generation runs up to ~120s server-side)
    // and trickles `data:` frames as phases complete. The shared OkHttpClient
    // caps reads at 30s, which aborts the stream mid-lookup and surfaces to the
    // UI as a "timed out" add. Derive a streaming client that disables the read
    // and overall-call timeouts (0 == no timeout) while keeping the connect
    // timeout, auth interceptor, and authenticator from the shared client.
    private val client: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** POST [path] (relative to the backend base URL) with [jsonBody]. */
    fun streamJsonPost(path: String, jsonBody: String): Flow<SseEvent> {
        val url = baseUrl.ensureTrailingSlash() + path.trimStart('/')
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody(jsonMedia))
            .build()
        return stream(request)
    }

    fun stream(request: Request): Flow<SseEvent> = flow {
        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val source = response.body?.source() ?: throw IOException("Empty SSE body")
            emitEvents(source) { emit(it) }
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Multipart POST + `text/event-stream` consumer (IMPL-AND-04 blood, IMPL-AND-05
 * DEXA). Builds the multipart body directly because OkHttp's EventSource factory
 * only issues GETs.
 */
@Singleton
class MultipartSseClient @Inject constructor(
    client: OkHttpClient,
) {
    // Same rationale as [SseClient]: blood/DEXA uploads stream progress frames
    // for the duration of server-side OCR + parsing, which exceeds the shared
    // client's 30s read timeout. Disable read/call timeouts for the stream.
    private val client: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    data class Part(
        val name: String,
        val fileName: String,
        val contentType: MediaType,
        val body: ByteArray,
    )

    fun stream(url: HttpUrl, parts: List<Part>): Flow<SseEvent> = flow {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                parts.forEach { p -> addFormDataPart(p.name, p.fileName, p.body.toRequestBody(p.contentType)) }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .post(multipart)
            .header("Accept", "text/event-stream")
            .build()

        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val source = response.body?.source() ?: throw IOException("Empty SSE body")
            emitEvents(source) { emit(it) }
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)
}

/** Shared SSE line parser: accumulates `event:` / `data:` lines per blank-line frame. */
internal suspend inline fun emitEvents(source: BufferedSource, emit: (SseEvent) -> Unit) {
    var eventName: String? = null
    val data = StringBuilder()
    while (!source.exhausted()) {
        currentCoroutineContext().ensureActive()
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    emit(SseEvent(eventName, data.toString()))
                    eventName = null
                    data.setLength(0)
                }
            }
            line.startsWith(":") -> Unit // comment / heartbeat
            line.startsWith("event:") -> eventName = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
            }
        }
    }
    if (data.isNotEmpty()) emit(SseEvent(eventName, data.toString()))
}

private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"
