package com.gte619n.healthfitness.core.chat

import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams the backend Server-Sent Events chat endpoint as a Kotlin
 * [Flow] of [ChatStreamEvent] (assumption 17).
 *
 * SSE over Retrofit is awkward, so this talks to OkHttp directly using the
 * SAME shared [OkHttpClient] (so the [com.gte619n.healthfitness.data.net.AuthInterceptor]
 * still injects the bearer token) and the same [BackendBaseUrl]. Commit and
 * thread-list calls stay on Retrofit (plain JSON) — see ChatApi in core-data.
 *
 * The parser is a manual chunked reader: a `BufferedReader` over the response
 * body, accumulating `event:`/`data:` lines and emitting one event per blank
 * line, per the SSE wire format. No third-party SSE library.
 */
@Singleton
class ChatSseClient @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    @BackendBaseUrl private val baseUrl: String,
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * POST [basePath] with `{threadId?, message}` and stream events. The flow
     * runs on IO; cancelling the collector cancels the underlying read loop.
     */
    fun stream(basePath: String, threadId: String?, message: String): Flow<ChatStreamEvent> = flow {
        val payload = ChatMessageBody(threadId = threadId, message = message)
        val requestBody = moshi.adapter(ChatMessageBody::class.java).toJson(payload)
            .toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(baseUrl.ensureTrailingSlash() + basePath.trimStart('/'))
            .header("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.takeIf { it.isNotBlank() }
                emit(ChatStreamEvent.Error(detail ?: "HTTP ${response.code}"))
                return@flow
            }
            val reader = response.body?.charStream()?.buffered()
            if (reader == null) {
                emit(ChatStreamEvent.Error("Empty response"))
                return@flow
            }

            var eventName: String? = null
            val data = StringBuilder()

            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        // Blank line dispatches the accumulated event.
                        dispatch(eventName, data.toString())?.let { emit(it) }
                        eventName = null
                        data.setLength(0)
                    }
                    line.startsWith("event:") -> eventName = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trim())
                    }
                    // ":" comment / heartbeat lines and anything else are ignored.
                }
            }
            // Flush a trailing event with no terminating blank line.
            dispatch(eventName, data.toString())?.let { emit(it) }
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun dispatch(name: String?, raw: String): ChatStreamEvent? {
        if (name == null) return null
        return when (name) {
            "token" -> ChatStreamEvent.Token(
                moshi.adapter(TokenData::class.java).fromJson(raw)?.text ?: "",
            )
            "proposal" -> ChatStreamEvent.Proposal(raw)
            "error" -> ChatStreamEvent.Error(
                moshi.adapter(ErrorData::class.java).fromJson(raw)?.error ?: "Chat failed",
            )
            "done" -> ChatStreamEvent.Done(
                moshi.adapter(DoneData::class.java).fromJson(raw)?.threadId,
            )
            else -> null
        }
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}

// --- Wire shapes (Moshi). Match GoalChatController exactly. ---

internal data class ChatMessageBody(
    val threadId: String?,
    val message: String,
)

private data class TokenData(val text: String?)
private data class ErrorData(val error: String?)
private data class DoneData(val threadId: String?)
