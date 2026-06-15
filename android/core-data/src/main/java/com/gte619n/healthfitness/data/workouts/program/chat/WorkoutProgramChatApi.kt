package com.gte619n.healthfitness.data.workouts.program.chat

import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramDeepDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Plain-JSON half of the workout-program designer chat (IMPL-AND-18). The SSE
 * stream lives in [WorkoutProgramChatClient] over the shared SseClient; commit
 * + thread list/delete are ordinary request/response, so they stay on Retrofit.
 *
 * Base path /api/me/workout-programs/chat (multi-tenant). Mirrors the goals
 * ChatApi shapes, plus the LOCKED workout-designer contract.
 */
interface WorkoutProgramChatApi {

    /**
     * Persist a (user-edited) proposal as a real program. Returns 201 + the deep
     * program on success, or 422 with `{ issues: [] }` so the card can re-flag
     * the offending fields — hence the raw [Response] so callers read both.
     */
    @POST("api/me/workout-programs/chat/{threadId}/commit")
    suspend fun commit(
        @Path("threadId") threadId: String,
        @Body body: CreateProgramRequestDto,
    ): Response<WorkoutProgramDeepDto>

    @GET("api/me/workout-programs/chat/threads")
    suspend fun listThreads(): List<ProgramChatThreadResponse>

    /** Persisted turns of a thread, oldest-first — used to rehydrate a reopened
     *  (or process-death-recreated) conversation. */
    @GET("api/me/workout-programs/chat/{threadId}")
    suspend fun messages(@Path("threadId") threadId: String): List<ProgramChatMessageResponse>

    /** 204 on success, 404 if the thread is unknown / belongs to another user. */
    @DELETE("api/me/workout-programs/chat/threads/{threadId}")
    suspend fun deleteThread(@Path("threadId") threadId: String): Response<Unit>
}
