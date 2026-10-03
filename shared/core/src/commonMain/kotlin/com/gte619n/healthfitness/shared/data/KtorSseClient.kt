package com.gte619n.healthfitness.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import com.gte619n.healthfitness.shared.presentation.workouts.FirstTurnEnvelope
import com.gte619n.healthfitness.shared.presentation.workouts.WorkoutDesignerViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    /**
     * Caps how long a read may block with NO bytes arriving (Ktor's
     * `socketTimeoutMillis`, the equivalent of the Android OkHttp reader's
     * `readTimeout`, #282). Guards a half-open connection where the backend never
     * emits and never closes — the read fails, the flow surfaces it, and the VM's
     * try/finally clears the "thinking" state instead of hanging forever. SSE
     * frames (incl. `:` heartbeats) reset the timer, so a busy stream never trips
     * it. Requires the client's `HttpTimeout` plugin to be installed (app DI).
     */
    private val idleTimeoutSeconds: Long = 120,
) : SseClient {

    override fun stream(basePath: String, threadId: String?, message: String): Flow<ChatStreamEvent> = flow {
        // Two wire shapes share ONE [SseClient] contract (goals + the workout
        // designer). The goals chat body is the plain `{message, threadId}`. The
        // designer's FIRST turn packs the setup (schedule + goalId) into the
        // message as a `DESIGNER_SETUP{…json…}` envelope (the shared VM can't add
        // params to this interface); here we split that envelope off and build the
        // richer `{threadId, message, schedule, goalId}` body the designer
        // controller expects. Follow-up designer turns + all goals turns are plain.
        val body: JsonElement = buildBody(threadId, message)
        val response = http.post("$baseUrl$basePath") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Accept, "text/event-stream") }
            setBody(body)
            if (idleTimeoutSeconds > 0) {
                timeout { socketTimeoutMillis = idleTimeoutSeconds * 1000 }
            }
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

    /**
     * Build the POST body for [stream]. A designer first turn arrives as
     * `DESIGNER_SETUP{json}` (see [WorkoutDesignerViewModel.FIRST_TURN_ENVELOPE_PREFIX]);
     * everything else is a plain `{message, threadId}`.
     */
    private fun buildBody(threadId: String?, message: String): JsonElement {
        val prefix = WorkoutDesignerViewModel.FIRST_TURN_ENVELOPE_PREFIX
        if (message.startsWith(prefix)) {
            val envelopeJson = message.removePrefix(prefix)
            val envelope = runCatching {
                json.decodeFromString(FirstTurnEnvelope.serializer(), envelopeJson)
            }.getOrNull()
            if (envelope != null) {
                return buildJsonObject {
                    // First-turn: no threadId yet (the controller mints one).
                    put("message", envelope.message)
                    envelope.goalId?.let { put("goalId", it) }
                    // `schedule` is the ProgramSchedule the controller requires to
                    // open a thread; ScheduleDto is wire-compatible (trainingDays +
                    // dayLocations keyed by DayOfWeek, which the backend's key
                    // deserializer accepts case-insensitively).
                    put("schedule", json.encodeToJsonElement(com.gte619n.healthfitness.shared.data.ScheduleDto.serializer(), envelope.schedule))
                }
            }
        }
        return buildJsonObject {
            put("message", message)
            threadId?.let { put("threadId", it) }
        }
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
