package com.gte619n.healthfitness.feature.workouts.program.chat

import com.gte619n.healthfitness.core.chat.ChatStreamEvent
import com.gte619n.healthfitness.data.net.SseClient
import com.gte619n.healthfitness.data.net.SseEvent
import com.gte619n.healthfitness.data.workouts.program.chat.ChatDoneData
import com.gte619n.healthfitness.data.workouts.program.chat.ChatErrorData
import com.gte619n.healthfitness.data.workouts.program.chat.ChatTokenData
import com.gte619n.healthfitness.data.workouts.program.chat.ProgramChatRequest
import com.gte619n.healthfitness.data.workouts.program.chat.ScheduleDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams the workout-program designer chat SSE endpoint as a [Flow] of
 * [ChatStreamEvent] (IMPL-AND-18). Unlike the goals chat (ChatSseClient, whose
 * body is just `{threadId, message}`), the workout chat's FIRST turn also
 * carries the form's schedule + optional goalId, so it builds a richer body and
 * uses the generic [SseClient.streamJsonPost] consumer.
 *
 * Maps the decoded [SseEvent]s onto the shared [ChatStreamEvent] sealed type,
 * mirroring ChatSseClient.dispatch (token/proposal/error/done). The `proposal`
 * event's raw JSON is carried opaquely so the ViewModel parses it into the deep
 * program DTO with Moshi.
 */
@Singleton
class WorkoutProgramChatClient @Inject constructor(
    private val sse: SseClient,
    private val moshi: Moshi,
) {
    private val requestAdapter = moshi.adapter(ProgramChatRequest::class.java)
    private val tokenAdapter = moshi.adapter(ChatTokenData::class.java)
    private val errorAdapter = moshi.adapter(ChatErrorData::class.java)
    private val doneAdapter = moshi.adapter(ChatDoneData::class.java)

    /**
     * POST the designer chat. [schedule] + [goalId] are sent ONLY when [threadId]
     * is null (the first turn opens the thread); later turns pass them as null so
     * the thread's fixed form drives the context.
     */
    fun stream(
        threadId: String?,
        message: String,
        schedule: ScheduleDto?,
        goalId: String?,
        programId: String? = null,
    ): Flow<ChatStreamEvent> {
        val body = ProgramChatRequest(
            threadId = threadId,
            message = message,
            // First turn only: the backend rejects an absent schedule when opening
            // a thread and ignores it on later turns.
            schedule = if (threadId == null) schedule else null,
            goalId = if (threadId == null) goalId else null,
            // IMPL-18b: bind a NEW thread to an active program for in-place editing.
            programId = if (threadId == null) programId else null,
        )
        val json = requestAdapter.toJson(body)
        // The backend caps the whole turn at 180s (SSE_TIMEOUT_MS) and closes the
        // emitter on timeout. Guard the one failure mode that outlives it — a
        // half-open connection that never emits and never closes — with a
        // client-side idle timeout above realistic first-token latency (model
        // warm-up + tool calls) but below the server cap, so a stuck stream
        // surfaces as an error instead of an endless "thinking" state.
        return sse.streamJsonPost(
            "api/me/workout-programs/chat",
            json,
            idleTimeoutSeconds = CHAT_IDLE_TIMEOUT_SECONDS,
        ).map { dispatch(it) }
    }

    private fun dispatch(event: SseEvent): ChatStreamEvent = when (event.event) {
        "token" -> ChatStreamEvent.Token(
            runCatching { tokenAdapter.fromJson(event.data)?.text }.getOrNull().orEmpty(),
        )
        "proposal" -> ChatStreamEvent.Proposal(event.data)
        "error" -> ChatStreamEvent.Error(
            runCatching { errorAdapter.fromJson(event.data)?.error }.getOrNull() ?: "Chat failed",
        )
        "done" -> ChatStreamEvent.Done(
            runCatching { doneAdapter.fromJson(event.data)?.threadId }.getOrNull(),
        )
        // Unknown / heartbeat frames carry no chat meaning; surface as an empty
        // token (filtered to "" so it appends nothing).
        else -> ChatStreamEvent.Token("")
    }

    private companion object {
        // Below the backend's 180s SSE cap; above worst-case first-token latency.
        const val CHAT_IDLE_TIMEOUT_SECONDS = 120L
    }
}
