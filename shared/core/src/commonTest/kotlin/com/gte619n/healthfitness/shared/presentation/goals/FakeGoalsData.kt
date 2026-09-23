package com.gte619n.healthfitness.shared.presentation.goals

import com.gte619n.healthfitness.shared.data.ChatRepository
import com.gte619n.healthfitness.shared.data.ChatStreamEvent
import com.gte619n.healthfitness.shared.data.ChatThreadResponse
import com.gte619n.healthfitness.shared.data.CommitResult
import com.gte619n.healthfitness.shared.data.GoalProposal
import com.gte619n.healthfitness.shared.data.GoalsRepository
import com.gte619n.healthfitness.shared.data.SseClient
import com.gte619n.healthfitness.shared.domain.goals.Goal
import com.gte619n.healthfitness.shared.domain.goals.GoalDeep
import com.gte619n.healthfitness.shared.domain.goals.GoalDomain
import com.gte619n.healthfitness.shared.domain.goals.GoalSource
import com.gte619n.healthfitness.shared.domain.goals.GoalStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow

/**
 * IMPL-IOS-01 Phase 3 Wave E3 (Goals) — commonTest fixtures for the goals VMs.
 * Plain KMP fakes (no MockK), authoring the vertical against the interfaces in
 * `data/GoalsRepositories.kt` before the concrete Room/Ktor + Ktor-SSE store
 * lands (Phase 1C).
 */

fun sampleGoal(
    id: String = "goal-1",
    title: String = "Lower ApoB",
    status: GoalStatus = GoalStatus.ACTIVE,
    domain: GoalDomain = GoalDomain.CARDIOVASCULAR,
): Goal = Goal(
    goalId = id,
    title = title,
    domain = domain,
    status = status,
    source = GoalSource.AI_GENERATED,
)

/** Fake goals repo whose per-status projection is a hot flow the test mutates. */
class FakeGoalsRepository(
    private val byStatus: Map<GoalStatus, MutableStateFlow<List<Goal>>> = emptyMap(),
    private val deep: MutableStateFlow<GoalDeep?> = MutableStateFlow(null),
    private val refreshThrows: Boolean = false,
) : GoalsRepository {
    var refreshCount = 0; private set

    override fun observeGoals(status: GoalStatus?): Flow<List<Goal>> =
        byStatus[status] ?: MutableStateFlow(emptyList())

    override fun observeGoalDeep(goalId: String): Flow<GoalDeep?> = deep

    override suspend fun refresh() {
        refreshCount++
        if (refreshThrows) throw RuntimeException("offline")
    }

    override suspend fun setStepDone(goalId: String, phaseId: String, stepId: String, done: Boolean): GoalDeep =
        deep.value ?: throw IllegalStateException("no deep goal")

    override suspend fun resetStepToAuto(goalId: String, phaseId: String, stepId: String): GoalDeep =
        deep.value ?: throw IllegalStateException("no deep goal")
}

/**
 * Fake SSE client that replays a scripted list of [ChatStreamEvent]s as a cold
 * flow — the token-accumulation test asserts the VM folds a scripted token
 * stream into one growing assistant message.
 */
class FakeSseClient(
    private val script: List<ChatStreamEvent>,
) : SseClient {
    var lastThreadId: String? = null; private set
    var lastMessage: String? = null; private set

    override fun stream(basePath: String, threadId: String?, message: String): Flow<ChatStreamEvent> {
        lastThreadId = threadId
        lastMessage = message
        return script.asFlow()
    }
}

/** Fake chat repo recording commits + serving a scripted commit outcome/thread list. */
class FakeChatRepository(
    private val threads: List<ChatThreadResponse> = emptyList(),
    private val commitResult: CommitResult = CommitResult.Created("goal-created-1"),
) : ChatRepository {
    val committed = mutableListOf<Pair<String, GoalProposal>>()
    val deleted = mutableListOf<String>()

    override suspend fun commit(threadId: String, proposal: GoalProposal): CommitResult {
        committed += threadId to proposal
        return commitResult
    }

    override suspend fun listThreads(): List<ChatThreadResponse> = threads

    override suspend fun deleteThread(threadId: String) {
        deleted += threadId
    }
}

/** Deterministic id source for message ids (u1, a1, u2, ...). */
class SequentialIdGenerator : IdGenerator {
    private var n = 0
    override fun next(): String = "id-${++n}"
}
