package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.data.workouts.program.DemoFrameDto
import com.gte619n.healthfitness.data.workouts.program.toDomain
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Read surface for the shared Exercise catalog. Used by the session logger's
 * mid-session substitution (#4): fetch the movements that are actually
 * executable at a given gym (the backend filters by that location's equipment
 * and by approved media) so the swap picker only offers real alternatives.
 */
interface ExerciseApi {
    /** Exercises executable at [locationId] (equipment- and media-filtered, server-side). */
    @GET("api/exercises/available")
    suspend fun available(@Query("locationId") locationId: String): List<AvailableExerciseDto>

    /**
     * Flag a demo frame as bad (#9). Owner-only server-side (403 otherwise);
     * the client also hides the affordance from non-owners. 204 on success.
     */
    @POST("api/exercises/{exerciseId}/flag-frame")
    suspend fun flagFrame(
        @Path("exerciseId") exerciseId: String,
        @Body body: FlagFrameRequest,
    )
}

/** Body for [ExerciseApi.flagFrame]: which frame, and an optional reason. */
data class FlagFrameRequest(val frameKey: String, val note: String? = null)

/**
 * A lean projection of the backend `ExerciseResponse` — only the fields the swap
 * picker and the resulting embedded summary need. Moshi ignores the many other
 * response fields, so the heavy catalog record decodes into this cheaply.
 */
data class AvailableExerciseDto(
    val exerciseId: String,
    val name: String,
    val primaryMuscles: List<String> = emptyList(),
    val formCues: List<String> = emptyList(),
    val demoFrames: List<DemoFrameDto> = emptyList(),
)

/** Map to the embedded [ExerciseSummary] the prescription carries after a swap. */
fun AvailableExerciseDto.toSummary(): ExerciseSummary = ExerciseSummary(
    exerciseId = exerciseId,
    name = name,
    primaryMuscles = primaryMuscles,
    formCues = formCues,
    demoFrames = demoFrames.map { it.toDomain() },
)
