package com.gte619n.healthfitness.data.workouts.progression

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * Retrofit service for the user-scoped progression console. All three endpoints
 * are user-scoped with no path params (the backend derives the user from the
 * auth token). Matches the backend contract exactly:
 *   GET api/me/progression/week-review        → [PatternReviewDto] array
 *   GET api/me/progression/block-parameters   → [BlockParametersDto]
 *   PUT api/me/progression/block-parameters   → [BlockParametersDto]
 */
interface ProgressionApi {
    @GET("api/me/progression/week-review")
    suspend fun weekReview(): List<PatternReviewDto>

    @GET("api/me/progression/block-parameters")
    suspend fun blockParameters(): BlockParametersDto

    @PUT("api/me/progression/block-parameters")
    suspend fun updateBlockParameters(@Body body: UpdateBlockParametersRequest): BlockParametersDto

    @GET("api/me/progression/strength")
    suspend fun strength(): List<ExerciseStrengthDto>

    @GET("api/me/progression/energy-balance")
    suspend fun energyBalance(): EnergyBalanceDto
}

/**
 * Partial update of the block parameters. Every field is nullable; an omitted
 * (null) field is left unchanged by the backend.
 */
data class UpdateBlockParametersRequest(
    val mode: String? = null,
    val successCriterion: String? = null,
    val expectedDriftPerDay: Double? = null,
)
