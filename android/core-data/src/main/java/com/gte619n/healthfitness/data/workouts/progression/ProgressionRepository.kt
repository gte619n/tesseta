package com.gte619n.healthfitness.data.workouts.progression

import com.gte619n.healthfitness.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.domain.workouts.progression.PatternReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network-read repository for the progression console. Deliberately simple: no
 * offline mirror (these are computed, always-fresh reads), so a failure surfaces
 * as a [Result] error the screen can retry. Everything runs on [Dispatchers.IO]
 * and maps DTOs to the domain types.
 */
@Singleton
class ProgressionRepository @Inject constructor(
    private val api: ProgressionApi,
) {
    suspend fun weekReview(): Result<List<PatternReview>> = withContext(Dispatchers.IO) {
        runCatching { api.weekReview().map { it.toDomain() } }
    }

    suspend fun blockParameters(): Result<BlockParameters> = withContext(Dispatchers.IO) {
        runCatching { api.blockParameters().toDomain() }
    }

    /**
     * Update any subset of the block parameters (omitted args are left unchanged
     * server-side). Returns the refreshed [BlockParameters].
     */
    suspend fun updateBlockParameters(
        mode: String? = null,
        successCriterion: String? = null,
        expectedDriftPerDay: Double? = null,
    ): Result<BlockParameters> = withContext(Dispatchers.IO) {
        runCatching {
            api.updateBlockParameters(
                UpdateBlockParametersRequest(
                    mode = mode,
                    successCriterion = successCriterion,
                    expectedDriftPerDay = expectedDriftPerDay,
                ),
            ).toDomain()
        }
    }
}
