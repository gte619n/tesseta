package com.gte619n.healthfitness.data.goals

import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.goals.GoalStatus
import com.gte619n.healthfitness.domain.goals.StepPatchRequest
import javax.inject.Inject
import javax.inject.Singleton

// Thin wrapper over GoalsApi exposing suspend functions for the ViewModels.
// Networking errors propagate as exceptions; the ViewModels map them to UI
// error state.
@Singleton
class GoalsRepository @Inject constructor(
    private val api: GoalsApi,
) {
    suspend fun goals(status: GoalStatus? = null): List<Goal> =
        api.getGoals(status?.name)

    suspend fun goalDeep(goalId: String): GoalDeep =
        api.getGoalDeep(goalId)

    /**
     * Toggle a step's done flag. Returns the refreshed deep goal so the UI sees
     * any cascade (phase completion, next phase activation, goal completion).
     */
    suspend fun setStepDone(
        goalId: String,
        phaseId: String,
        stepId: String,
        done: Boolean,
    ): GoalDeep {
        api.patchStep(goalId, phaseId, stepId, StepPatchRequest(done = done))
        return api.getGoalDeep(goalId)
    }

    /** Clear a manual override and re-run auto-evaluation for the step. */
    suspend fun resetStepToAuto(
        goalId: String,
        phaseId: String,
        stepId: String,
    ): GoalDeep {
        api.patchStep(goalId, phaseId, stepId, StepPatchRequest(resetToAuto = true))
        return api.getGoalDeep(goalId)
    }

    suspend fun reevaluate(goalId: String): GoalDeep =
        api.reevaluate(goalId)
}
