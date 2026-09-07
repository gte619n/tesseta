package com.gte619n.healthfitness.data.biometrics

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

// Retrofit service for the biometrics settings section.
interface BiometricsService {
    @GET("api/me/biometrics")
    suspend fun list(): List<BiometricSummaryDto>

    // Replace the hidden set (full set, not a delta); returns the fresh summaries.
    @PUT("api/me/biometrics/visibility")
    suspend fun setVisibility(@Body body: VisibilityBody): List<BiometricSummaryDto>
}

// Plain data class; Moshi reflection adapter handles (de)serialization.
data class BiometricSummaryDto(
    val key: String,
    val label: String,
    val unit: String,
    val visible: Boolean,
    val latestValue: Double?,
    val latestAt: String?, // ISO-8601 instant | null
    val observationsLast60d: Int,
    val avgIntervalDays: Double?,
)

data class VisibilityBody(val hidden: List<String>)
