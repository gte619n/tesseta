package com.gte619n.healthfitness.data.goals

import com.gte619n.healthfitness.domain.goals.CommitResponse
import com.gte619n.healthfitness.domain.goals.GoalProposal
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Plain-JSON half of the Goals chat surface (IMPL-12 step 9). The SSE stream
 * itself is NOT here — it is awkward over Retrofit, so it lives in
 * core-chat's ChatSseClient over the shared OkHttpClient. Commit and
 * thread-list are ordinary JSON request/response, so they stay on Retrofit.
 *
 * Base path /api/me/goals/chat (assumption 1 — multi-tenant).
 */
interface ChatApi {

    /**
     * Persist a (user-edited) proposal. Returns 200 + {goalId} on success, or
     * 400 with the re-flagged GoalProposal so the card can re-render inline
     * validation errors — hence the raw [Response] so callers can read both.
     */
    @POST("api/me/goals/chat/{threadId}/commit")
    suspend fun commit(
        @Path("threadId") threadId: String,
        @Body proposal: GoalProposal,
    ): Response<CommitResponse>

    @GET("api/me/goals/chat/threads")
    suspend fun listThreads(): List<ChatThreadResponse>
}

/** Mirrors backend api/goals/dto/ChatThreadResponse. */
data class ChatThreadResponse(
    val threadId: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
)
