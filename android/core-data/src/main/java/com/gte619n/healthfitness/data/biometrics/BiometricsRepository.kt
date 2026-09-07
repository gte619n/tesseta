package com.gte619n.healthfitness.data.biometrics

import com.gte619n.healthfitness.data.profile.ProfileRepository
import com.gte619n.healthfitness.domain.biometrics.BiometricSummary
import java.time.Instant
import javax.inject.Inject

// Concrete @Inject repository for the biometrics settings section.
class BiometricsRepository @Inject constructor(
    private val service: BiometricsService,
    private val profile: ProfileRepository,
) {

    suspend fun list(): Result<List<BiometricSummary>> = runCatching {
        service.list().map { it.toDomain() }
    }

    suspend fun setVisibility(hidden: List<String>): Result<List<BiometricSummary>> = runCatching {
        val fresh = service.setVisibility(VisibilityBody(hidden)).map { it.toDomain() }
        // Reflect the new hidden set in the profile mirror so the dashboard drops
        // (or restores) cards promptly, rather than waiting for the next sync pull.
        runCatching { profile.patchHiddenBiometricsLocally(hidden) }
        fresh
    }

    private fun BiometricSummaryDto.toDomain() = BiometricSummary(
        key = key,
        label = label,
        unit = unit,
        visible = visible,
        latestValue = latestValue,
        latestAt = latestAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        observationsLast60d = observationsLast60d,
        avgIntervalDays = avgIntervalDays,
    )
}
