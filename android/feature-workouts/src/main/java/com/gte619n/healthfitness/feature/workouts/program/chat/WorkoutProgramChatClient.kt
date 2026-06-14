package com.gte619n.healthfitness.feature.workouts.program.chat

import com.gte619n.healthfitness.core.chat.ChatStreamEvent
import com.gte619n.healthfitness.data.net.SseClient
import com.gte619n.healthfitness.data.net.SseEvent
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
    private val tokenAdapter = moshi.adapter(TokenData::class.java)
    private val errorAdapter = moshi.adapter(ErrorData::class.java)
    private val doneAdapter = moshi.adapter(DoneData::class.java)

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
    ): Flow<ChatStreamEvent> {
        val body = ProgramChatRequest(
            threadId = threadId,
            message = message,
            // First turn only: the backend rejects an absent schedule when opening
            // a thread and ignores it on later turns.
            schedule = if (threadId == null) schedule else null,
            goalId = if (threadId == null) goalId else null,
        )
        val json = requestAdapter.toJson(body)
        return sse.streamJsonPost("api/me/workout-programs/chat", json).map { dispatch(it) }
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

    private data class TokenData(val text: String?)
    private data class ErrorData(val error: String?)
    private data class DoneData(val threadId: String?)
}
