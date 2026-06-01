package com.gte619n.healthfitness.data.bodycomposition.dto

/**
 * One body-composition reading row from
 * `GET /api/me/body-composition`. Plain data class parsed by Moshi
 * reflection (no @JsonClass; the network Moshi uses KotlinJsonAdapterFactory).
 */
data class BodyCompositionReadingDto(
    val recordId: String?,
    val metric: String?,
    val value: Double?,
    val sampleTime: String?,
    val sourcePlatform: String?,
    val recordingMethod: String?,
)
