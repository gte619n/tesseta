package com.gte619n.healthfitness.domain.bodycomposition

import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface BodyCompositionRepository {
    /** Latest snapshot + 90d weight series. Hot-replays on refresh. */
    fun observeSnapshot(): Flow<BodyCompositionSnapshot>

    suspend fun refresh()

    suspend fun pointsInRange(
        metric: BodyCompositionMetric,
        from: Instant,
        to: Instant,
    ): List<BodyCompositionPoint>
}
