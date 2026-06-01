package com.gte619n.healthfitness.data.workouts.program

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Read-only REST surface for workout programs (IMPL-15). Base path is
 * /api/me/workout-programs (multi-tenant). No SSE, no mutation — the phone is
 * a viewer in IMPL-AND-15.
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
}
