package com.gte619n.healthfitness.data.goals

import com.gte619n.healthfitness.data.db.dao.GoalDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.goals.GoalStatus
import com.gte619n.healthfitness.domain.goals.StepPatchRequest
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — partially Room-backed goals repository.
 *
 * The **goals list** ([goals]) reads from the `goals` mirror (D8); the network
 * only fills it (one-shot on a cold miss + the background SyncEngine pull). Each
 * row's `payloadJson` is the full [Goal] the list screen consumes.
 *
 * The **deep goal** ([goalDeep]) and the **step intents**
 * ([setStepDone]/[resetStepToAuto]/[reevaluate]) stay on the network path by
 * design (D9):
 *  - `GoalDeep` is a nested aggregate (phases + steps) whose step `done`/`doneAt`
 *    are **server-derived**; reassembling it from the flat `goals`/`goalPhases`/
 *    `goalSteps` mirror tables and keeping the server-evaluated cascade correct
 *    offline is out of scope for this phase.
 *  - A manual step toggle is already an **explicit intent** (`PATCH .../steps/{sid}`)
 *    that the server re-evaluates — exactly the D9 contract. It must NOT be replayed
 *    as a raw derived-doc write, so it is intentionally NOT routed through the
 *    outbox. After any intent we re-fetch the deep goal so the cascade surfaces, and
 *    refresh the goal's mirror row so the list reflects the new status.
 */
@Singleton
class GoalsRepository @Inject constructor(
    private val api: GoalsApi,
    private val dao: GoalDao,
    private val support: MirrorRepositorySupport,
    moshi: Moshi,
) {
    private val goalAdapter = moshi.adapter(Goal::class.java)

    suspend fun goals(status: GoalStatus? = null): List<Goal> {
        if (support.killSwitchOn()) return api.getGoals(status?.name)
        if (dao.observeActive().first().isEmpty()) fillMirror()
        return dao.observeActive().first()
            .mapNotNull { runCatching { goalAdapter.fromJson(it.payloadJson) }.getOrNull() }
            .filter { status == null || it.status == status }
    }

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
        return refreshedDeep(goalId)
    }

    /** Clear a manual override and re-run auto-evaluation for the step. */
    suspend fun resetStepToAuto(
        goalId: String,
        phaseId: String,
        stepId: String,
    ): GoalDeep {
        api.patchStep(goalId, phaseId, stepId, StepPatchRequest(resetToAuto = true))
        return refreshedDeep(goalId)
    }

    suspend fun reevaluate(goalId: String): GoalDeep {
        api.reevaluate(goalId)
        return refreshedDeep(goalId)
    }

    /** Re-fetch the deep goal and refresh the list mirror row for the goal. */
    private suspend fun refreshedDeep(goalId: String): GoalDeep {
        val deep = api.getGoalDeep(goalId)
        support.refreshInto(
            MirrorTables.GOALS,
            listOf(
                MirrorRepositorySupport.RefreshRow(
                    id = deep.goalId,
                    payloadJson = goalAdapter.toJson(deep.toGoal()),
                    lastUpdate = System.currentTimeMillis(),
                ),
            ),
        )
        return deep
    }

    private suspend fun fillMirror() {
        val goals = api.getGoals(null)
        support.refreshInto(
            MirrorTables.GOALS,
            goals.map {
                MirrorRepositorySupport.RefreshRow(
                    id = it.goalId,
                    payloadJson = goalAdapter.toJson(it),
                    lastUpdate = System.currentTimeMillis(),
                )
            },
        )
    }

    private fun GoalDeep.toGoal() = Goal(
        goalId = goalId,
        title = title,
        description = description,
        domain = domain,
        status = status,
        startDate = startDate,
        targetDate = targetDate,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        phaseOrder = phaseOrder,
        source = source,
    )
}
