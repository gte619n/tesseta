package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.data.workouts.session.CompleteSessionRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * REST surface for workout programs. Base path is /api/me/workout-programs
 * (multi-tenant). Reads mirror the IMPL-15 surface; the single mutation is the
 * ADR-0012 session-completion upsert (normally replayed through the offline
 * outbox — [completeSession] is the live-network/kill-switch path).
 */
interface WorkoutProgramApi {
    @GET("api/me/workout-programs")
    suspend fun list(): List<WorkoutProgramDto>

    @GET("api/me/workout-programs/{id}")
    suspend fun get(@Path("id") id: String): WorkoutProgramDeepDto

    @GET("api/me/workout-programs/{id}/calendar")
    suspend fun calendar(
        @Path("id") id: String,
        @Query("from") from: String, // ISO LocalDate (yyyy-MM-dd)
        @Query("to") to: String,
    ): List<ScheduledWorkoutDto>

    /**
     * Activate a program: the backend materializes its phases into dated
     * sessions (forward-only) and marks it ACTIVE. Returns the materialized
     * sessions. Online-only, like the chat commit.
     */
    @POST("api/me/workout-programs/{id}/activate")
    suspend fun activate(@Path("id") id: String): List<ScheduledWorkoutDto>

    /**
     * IMPL-STAB G4 — metadata-only program edit (title/description). Null fields
     * are left unchanged by the backend; phases/schedule are not touched here
     * (structural edits go through the conversational designer). Returns the
     * updated deep program.
     */
    @PATCH("api/me/workout-programs/{id}")
    suspend fun updateDetails(
        @Path("id") id: String,
        @Body body: UpdateProgramDetailsRequest,
    ): WorkoutProgramDeepDto

    /**
     * ADR-0012 / IMPL-17 D1+D2 — idempotent completion upsert for one
     * scheduled session. Returns the updated scheduled workout.
     */
    @PUT("api/me/workout-programs/{id}/sessions/{scheduledId}")
    suspend fun completeSession(
        @Path("id") id: String,
        @Path("scheduledId") scheduledId: String,
        @Body body: CompleteSessionRequest,
    ): ScheduledWorkoutDto

    /**
     * IMPL-COACH — best-effort AI post-workout recap for a completed session.
     * Fetched separately from [completeSession] because the phone's completion
     * is offline-first (the outbox replays the PUT asynchronously). `recap` is
     * null until the session is COMPLETED server-side or when the coach is
     * unavailable; the caller degrades to the numeric summary.
     */
    @GET("api/me/workout-programs/{id}/sessions/{scheduledId}/recap")
    suspend fun sessionRecap(
        @Path("id") id: String,
        @Path("scheduledId") scheduledId: String,
    ): SessionRecapDto
}

/** IMPL-COACH: the AI recap payload (null until available). */
data class SessionRecapDto(val recap: String? = null)
