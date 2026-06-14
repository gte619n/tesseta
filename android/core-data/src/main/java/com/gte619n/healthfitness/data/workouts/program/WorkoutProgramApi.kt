package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.data.workouts.session.CompleteSessionRequest
import retrofit2.http.Body
import retrofit2.http.GET
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
     * ADR-0012 / IMPL-17 D1+D2 — idempotent completion upsert for one
     * scheduled session. Returns the updated scheduled workout.
     */
    @PUT("api/me/workout-programs/{id}/sessions/{scheduledId}")
    suspend fun completeSession(
        @Path("id") id: String,
        @Path("scheduledId") scheduledId: String,
        @Body body: CompleteSessionRequest,
    ): ScheduledWorkoutDto
}
