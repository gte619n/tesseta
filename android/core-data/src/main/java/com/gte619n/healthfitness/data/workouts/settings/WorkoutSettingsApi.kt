package com.gte619n.healthfitness.data.workouts.settings

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * Wire DTO for `GET/PUT /api/me/workout-programs/settings`. On PUT, each field is
 * applied only when non-null, so the streak stepper and the preferences editor can
 * each send just their own field without clobbering the other.
 */
data class WorkoutSettingsDto(
    val weeklyStreakTarget: Int? = null,
    val preferences: String? = null,
)

interface WorkoutSettingsApi {

    @GET("api/me/workout-programs/settings")
    suspend fun get(): WorkoutSettingsDto

    @PUT("api/me/workout-programs/settings")
    suspend fun put(@Body body: WorkoutSettingsDto): WorkoutSettingsDto
}
