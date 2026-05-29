package com.gte619n.healthfitness.data.goals

import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.goals.Step
import com.gte619n.healthfitness.domain.goals.StepPatchRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// Multi-tenant Goals REST surface. Base path is /api/me/goals (see IMPL-12
// remaining-assumptions item 1 — the whole backend is multi-tenant).
interface GoalsApi {

    @GET("api/me/goals")
    suspend fun getGoals(@Query("status") status: String? = null): List<Goal>

    @GET("api/me/goals/{id}")
    suspend fun getGoalDeep(@Path("id") goalId: String): GoalDeep

    // Backend PATCH .../steps/{sid} returns a single StepResponse (not Phase).
    @PATCH("api/me/goals/{id}/phases/{pid}/steps/{sid}")
    suspend fun patchStep(
        @Path("id") goalId: String,
        @Path("pid") phaseId: String,
        @Path("sid") stepId: String,
        @Body body: StepPatchRequest,
    ): Step

    // Backend POST .../reevaluate returns 204 No Content.
    @POST("api/me/goals/{id}/reevaluate")
    suspend fun reevaluate(@Path("id") goalId: String): Unit
}
