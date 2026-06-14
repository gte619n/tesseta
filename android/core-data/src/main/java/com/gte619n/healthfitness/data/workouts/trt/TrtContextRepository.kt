package com.gte619n.healthfitness.data.workouts.trt

import com.gte619n.healthfitness.domain.workouts.trt.TrtContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the designer chat's TRT/labs context (IMPL-AND-18). Online-only (the
 * chat itself is online over SSE), so this is a plain network read with no
 * mirror — the resulting program is read by the existing sync once created.
 */
@Singleton
class TrtContextRepository @Inject constructor(
    private val api: TrtContextApi,
) {
    suspend fun fetch(): Result<TrtContext> = runCatching { api.trtContext().toDomain() }
}
