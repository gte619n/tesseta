package com.gte619n.healthfitness.data.workouts.trt

import retrofit2.http.GET

/**
 * TRT/labs decision-support context for the workout-program designer chat
 * (IMPL-AND-18 / ADR-0015). One read: the user's monitoring-panel markers vs.
 * range with trend + status, plus any hard danger flags. Online-only — reuses
 * the labs the app already syncs.
 */
interface TrtContextApi {
    @GET("api/me/workout-programs/chat/trt-context")
    suspend fun trtContext(): TrtContextDto
}
