package com.gte619n.healthfitness.data.workouts.settings

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/** Wire DTO for `GET/PUT /api/me/workout-programs/settings`. */
data class WorkoutSettingsDto(
    val weeklyStreakTarget: Int? = null,
)

interface WorkoutSettingsApi {

    @GET("api/me/workout-programs/settings")
    suspend fun get(): WorkoutSettingsDto

    @PUT("api/me/workout-programs/settings")
    suspend fun put(@Body body: WorkoutSettingsDto): WorkoutSettingsDto
}
