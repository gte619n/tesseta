package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.goals.Goal
import com.gte619n.healthfitness.shared.domain.goals.GoalDeep
import com.gte619n.healthfitness.shared.domain.goals.GoalStatus
import com.gte619n.healthfitness.shared.domain.goals.Phase
import com.gte619n.healthfitness.shared.domain.goals.Step
import com.gte619n.healthfitness.shared.sync.MirrorTables
import com.gte619n.healthfitness.shared.sync.SqlDelightMirrorStore
import com.gte619n.healthfitness.shared.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 (#2 follow-up) — mirror-backed [GoalsRepository]. The goals list and the
 * deep (roadmap) goal read from the on-device mirror (populated by the sync delta
 * pull), so they render offline + instantly on cold start; a first-collect triggers a
 * best-effort pull. The deep goal is ASSEMBLED client-side from the flat GOALS /
 * GOAL_PHASES / GOAL_STEPS rows ([assembleGoalDeep]).
 *
 * Mutations (setStepDone / resetStepToAuto) delegate to the networked [http] impl
 * (online PATCH returning the authoritative GoalDeep the VM swaps in optimistically);
 * the next delta pull reconciles the mirror.
 */
class MirrorGoalsRepository(
    private val http: HttpGoalsRepository,
    private val mirror: SqlDelightMirrorStore,
    private val engine: SyncEngine,
    private val json: Json = LENIENT,
) : GoalsRepository by http {

    override fun observeGoals(status: GoalStatus?): Flow<List<Goal>> =
        mirror.observeActiveRecords(MirrorTables.GOALS)
            .onStart { runCatching { engine.pull() } }
            .map { records ->
                val goals = records.mapNotNull { decode(Goal.serializer(), it.payloadJson) }
                if (status == null) goals else goals.filter { it.status == status }
            }

    override fun observeGoalDeep(goalId: String): Flow<GoalDeep?> =
        combine(
            mirror.observeActiveRecords(MirrorTables.GOALS),
            mirror.observeActiveRecords(MirrorTables.GOAL_PHASES),
            mirror.observeActiveRecords(MirrorTables.GOAL_STEPS),
        ) { goalRows, phaseRows, stepRows ->
            val goal = goalRows.mapNotNull { decode(Goal.serializer(), it.payloadJson) }
                .firstOrNull { it.goalId == goalId } ?: return@combine null
            assembleGoalDeep(
                goal = goal,
                phases = phaseRows.mapNotNull { decode(Phase.serializer(), it.payloadJson) },
                steps = stepRows.mapNotNull { decode(Step.serializer(), it.payloadJson) },
            )
        }.onStart { runCatching { engine.pull() } }

    override suspend fun refresh() {
        runCatching { engine.pull() }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, payloadJson: String): T? =
        runCatching { json.decodeFromString(serializer, payloadJson) }.getOrNull()

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    }
}
