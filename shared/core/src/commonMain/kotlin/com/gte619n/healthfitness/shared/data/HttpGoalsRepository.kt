package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.goals.Goal
import com.gte619n.healthfitness.shared.domain.goals.GoalDeep
import com.gte619n.healthfitness.shared.domain.goals.GoalStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * IMPL-IOS-01 Phase 1C — online-first [GoalsRepository] over the existing
 * `GET /api/me/goals` endpoint. The list (observe + refresh) is backed by a
 * MutableStateFlow refreshed lazily/on refresh; [observeGoals] filters it by
 * status client-side.
 *
 * The deep-goal roadmap methods (observeGoalDeep/setStepDone/resetStepToAuto)
 * belong to the Goal roadmap screen, which isn't wired yet — left as online-first
 * stubs so the list screen compiles.
 */
class HttpGoalsRepository(private val client: HttpClient) : GoalsRepository {

    private val all = MutableStateFlow<List<Goal>>(emptyList())
    private var loaded = false

    override fun observeGoals(status: GoalStatus?): Flow<List<Goal>> =
        all.map { list -> status?.let { s -> list.filter { it.status == s } } ?: list }
            .onStart { if (!loaded) refresh() }

    override suspend fun refresh() {
        all.value = client.get("api/me/goals").body()
        loaded = true
    }

    override fun observeGoalDeep(goalId: String): Flow<GoalDeep?> = flowOf(null)

    override suspend fun setStepDone(
        goalId: String,
        phaseId: String,
        stepId: String,
        done: Boolean,
    ): GoalDeep = error("Goal roadmap not wired yet (Phase 1C)")

    override suspend fun resetStepToAuto(
        goalId: String,
        phaseId: String,
        stepId: String,
    ): GoalDeep = error("Goal roadmap not wired yet (Phase 1C)")
}
