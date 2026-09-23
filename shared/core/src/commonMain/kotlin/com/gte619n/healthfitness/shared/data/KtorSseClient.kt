package com.gte619n.healthfitness.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * IMPL-IOS-01 — concrete [SseClient] (closes the Phase-4 audit gap "SSE transport
 * unimplemented"). One shared Ktor implementation serves BOTH SSE consumers — the
 * Goals coach chat and the Workout Designer — so neither platform hand-rolls a
 * reader. Ktor's engine is per-platform (`OkHttp` on Android, `Darwin` on iOS,
 * wired by the app's DI), but the SSE PARSING lives here in common code.
 *
 * Wire format mirrors the Android `core-chat` OkHttp reader: the backend streams
 * `text/event-stream`; each event is one or more `field: value` lines terminated
 * by a blank line. We recognize:
 *   - `event: proposal` + `data: {json}`  → [ChatStreamEvent.Proposal]
 *   - `event: error`    + `data: {msg}`    → [ChatStreamEvent.Error]
 *   - `event: done`     + `data: {json?}`  → [ChatStreamEvent.Done] (threadId)
 *   - bare `data: {token}`                 → [ChatStreamEvent.Token]
 *
 * The [ChatStreamEvent] fold that turns these into UI state already lives in
 * `GoalsChatViewModel` / `WorkoutDesignerViewModel` and is unit-tested against a
 * fake `SseClient`; this class is the real transport those fakes stand in for.
 */
class KtorSseClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SseClient {

    override fun stream(basePath: String, threadId: String?, message: String): Flow<ChatStreamEvent> = flow {
        val response = http.post("$baseUrl$basePath") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Accept, "text/event-stream") }
            setBody(ChatSendBody(message = message, threadId = threadId))
        }
        val channel = response.bodyAsChannel()

        var event: String? = null
        val data = StringBuilder()

        suspend fun dispatch() {
            if (data.isEmpty() && event == null) return
            val payload = data.toString()
            when (event) {
                "proposal" -> emit(ChatStreamEvent.Proposal(payload))
                "error" -> emit(ChatStreamEvent.Error(payload.ifBlank { "stream error" }))
                "done" -> emit(ChatStreamEvent.Done(threadIdFrom(payload)))
                else -> if (payload.isNotEmpty()) emit(ChatStreamEvent.Token(payload))
            }
            event = null
            data.clear()
        }

        while (true) {
            val line = channel.readUTF8Line() ?: break
            when {
                // Blank line = end of one event → dispatch what we accumulated.
                line.isEmpty() -> dispatch()
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
                // ':' comment / heartbeat lines and unknown fields are ignored.
            }
        }
        dispatch() // flush a trailing event with no terminating blank line
    }

    /** `done` data may be a bare id, or `{"threadId": "..."}`. Tolerate both. */
    private fun threadIdFrom(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("{")) {
            runCatching {
                (json.parseToJsonElement(trimmed) as? JsonObject)
                    ?.get("threadId")?.jsonPrimitive?.content
            }.getOrNull()
        } else {
            trimmed
        }
    }
}

@kotlinx.serialization.Serializable
private data class ChatSendBody(val message: String, val threadId: String?)
