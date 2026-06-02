package com.gte619n.healthfitness.data.goals

import com.gte619n.healthfitness.data.db.dao.GoalDao
import com.gte619n.healthfitness.data.db.dao.GoalPhaseDao
import com.gte619n.healthfitness.data.db.dao.GoalStepDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalDeep
import com.gte619n.healthfitness.domain.goals.GoalStatus
import com.gte619n.healthfitness.domain.goals.Phase
import com.gte619n.healthfitness.domain.goals.PhaseStatus
import com.gte619n.healthfitness.domain.goals.Step
import com.gte619n.healthfitness.domain.goals.StepKind
import com.gte619n.healthfitness.domain.goals.StepMetricBinding
import com.gte619n.healthfitness.domain.goals.StepPatchRequest
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5 + #26) — Room-backed, offline-first goals repository.
 *
 * **List** ([goals]) reads from the `goals` mirror (D8); the network only fills it.
 *
 * **Deep goal** ([goalDeep]) is now assembled OFFLINE from the flat
 * `goals`/`goalPhases`/`goalSteps` mirror tables ([assembleDeep]) so the roadmap
 * renders without a network round-trip. When online we [refreshDeep] (fetch the
 * authoritative `GoalDeep` and fan it out into the three flat tables); offline (or
 * on a fetch failure) we assemble from whatever the last sync left, keeping the
 * `phaseOrder`/`stepOrder` cascade correct.
 *
 * **Structural phase/step CRUD** ([createPhase]/[updatePhase]/[deletePhase],
 * [createStep]/[updateStep]/[deleteStep]) is optimistic + outbox (D7): the mirror
 * row appears instantly (PENDING) and the drain replays it to the real
 * phase/step controller via [com.gte619n.healthfitness.data.sync.OutboxEndpointRegistry].
 * Composite ids carry the parent path: phases are `"<goalId>/<phaseId>"`, steps are
 * `"<goalId>/<phaseId>/<stepId>"`.
 *
 * **Step done/doneAt (D9)** is SERVER-DERIVED. A manual toggle stays an explicit
 * `PATCH .../steps/{sid}` **intent** ([setStepDone]/[resetStepToAuto]) routed as a
 * normal network mutation — it is NEVER enqueued through the structural-edit
 * outbox, so a replay can never clobber the server-evaluated `done`/`doneAt`/
 * `manualOverride`/`metricRegressed` fields. Reevaluate ([reevaluate]) stays online.
 */
@Singleton
class GoalsRepository @Inject constructor(
    private val api: GoalsApi,
    private val dao: GoalDao,
    private val phaseDao: GoalPhaseDao,
    private val stepDao: GoalStepDao,
    private val support: MirrorRepositorySupport,
    moshi: Moshi,
) {
    private val goalAdapter = moshi.adapter(Goal::class.java)
    private val phaseAdapter = moshi.adapter(Phase::class.java)
    private val stepAdapter = moshi.adapter(Step::class.java)

    suspend fun goals(status: GoalStatus? = null): List<Goal> {
        if (support.killSwitchOn()) return api.getGoals(status?.name)
        if (dao.observeActive().first().isEmpty()) fillMirror()
        return dao.observeActive().first()
            .mapNotNull { runCatching { goalAdapter.fromJson(it.payloadJson) }.getOrNull() }
            .filter { status == null || it.status == status }
    }

    /**
     * The roadmap aggregate. When the kill-switch is on, read live. Otherwise try a
     * live refresh (fanning out into the flat mirror tables), then ALWAYS assemble
     * from the mirror so the result reflects any optimistic offline phase/step edit.
     * On a refresh failure (offline) we assemble from the last-synced mirror.
     */
    suspend fun goalDeep(goalId: String): GoalDeep {
        if (support.killSwitchOn()) return api.getGoalDeep(goalId)
        runCatching { refreshDeep(goalId) }
        return assembleDeep(goalId) ?: api.getGoalDeep(goalId)
    }

    /** Fetch the authoritative deep goal and fan it into the flat mirror tables. */
    private suspend fun refreshDeep(goalId: String): GoalDeep {
        val deep = api.getGoalDeep(goalId)
        support.refreshInto(
            MirrorTables.GOALS,
            listOf(refreshRow(deep.goalId, goalAdapter.toJson(deep.toGoal()))),
        )
        // Use the SAME composite ids the optimistic writes mint so a refresh after
        // an offline create reconciles the existing row instead of duplicating it.
        support.refreshInto(
            MirrorTables.GOAL_PHASES,
            deep.phases.map {
                refreshRow(compositeId(deep.goalId, it.phaseId), phaseAdapter.toJson(it.copy(steps = emptyList())))
            },
        )
        support.refreshInto(
            MirrorTables.GOAL_STEPS,
            deep.phases.flatMap { it.steps }.map {
                refreshRow(stepCompositeId(deep.goalId, it.phaseId, it.stepId), stepAdapter.toJson(it))
            },
        )
        return deep
    }

    /**
     * Assemble [GoalDeep] from the flat mirror tables, preserving the
     * `phaseOrder`/`stepOrder` cascade. Returns null if the goal isn't mirrored.
     */
    private suspend fun assembleDeep(goalId: String): GoalDeep? {
        val goal = dao.getById(goalId)?.let { decode(it.payloadJson, goalAdapter) } ?: return null
        // Dedup by logical id: the delta-pull mirror row (keyed by the backend id)
        // and a refreshDeep/optimistic row (keyed by the composite id) can both be
        // present for the same phase/step; the newest wins by lastUpdate order
        // (observeActive is newest-first) so distinctBy keeps the freshest.
        val phasesForGoal = phaseDao.observeActive().first()
            .mapNotNull { decode(it.payloadJson, phaseAdapter) }
            .filter { it.goalId == goalId }
            .distinctBy { it.phaseId }
        val stepsForGoal = stepDao.observeActive().first()
            .mapNotNull { decode(it.payloadJson, stepAdapter) }
            .filter { it.goalId == goalId }
            .distinctBy { it.stepId }
            .groupBy { it.phaseId }

        val orderedPhases = goal.phaseOrder
            .mapNotNull { pid -> phasesForGoal.firstOrNull { it.phaseId == pid } }
            .ifEmpty { phasesForGoal.sortedBy { it.orderIndex } }
            .map { phase ->
                val steps = stepsForGoal[phase.phaseId].orEmpty()
                val orderedSteps = phase.stepOrder
                    .mapNotNull { sid -> steps.firstOrNull { it.stepId == sid } }
                    .ifEmpty { steps.sortedBy { it.orderIndex } }
                phase.copy(steps = orderedSteps)
            }

        return GoalDeep(
            goalId = goal.goalId,
            title = goal.title,
            description = goal.description,
            domain = goal.domain,
            status = goal.status,
            startDate = goal.startDate,
            targetDate = goal.targetDate,
            createdAt = goal.createdAt,
            updatedAt = goal.updatedAt,
            completedAt = goal.completedAt,
            phaseOrder = goal.phaseOrder,
            source = goal.source,
            phases = orderedPhases,
        )
    }

    // --- Structural phase CRUD (optimistic + outbox, #26) ---

    suspend fun createPhase(
        goalId: String,
        title: String,
        description: String? = null,
    ): Phase {
        val phaseId = UUID.randomUUID().toString()
        val existingCount = phaseDao.observeActive().first()
            .mapNotNull { decode(it.payloadJson, phaseAdapter) }
            .count { it.goalId == goalId }
        val phase = Phase(
            phaseId = phaseId,
            goalId = goalId,
            title = title,
            description = description,
            orderIndex = existingCount,
            status = if (existingCount == 0) PhaseStatus.ACTIVE else PhaseStatus.LOCKED,
            stepOrder = emptyList(),
            steps = emptyList(),
        )
        support.createLocal(
            table = MirrorTables.GOAL_PHASES,
            id = compositeId(goalId, phaseId),
            payloadJson = phaseAdapter.toJson(phase),
            lastUpdate = System.currentTimeMillis(),
        )
        // Reflect the new phase in the goal's phaseOrder mirror so the cascade holds.
        appendToGoalPhaseOrder(goalId, phaseId)
        return phase
    }

    suspend fun updatePhase(goalId: String, phase: Phase) {
        support.updateLocal(
            table = MirrorTables.GOAL_PHASES,
            id = compositeId(goalId, phase.phaseId),
            payloadJson = phaseAdapter.toJson(phase.copy(steps = emptyList())),
            lastUpdate = System.currentTimeMillis(),
        )
    }

    suspend fun deletePhase(goalId: String, phaseId: String) {
        support.deleteLocal(
            MirrorTables.GOAL_PHASES,
            compositeId(goalId, phaseId),
            System.currentTimeMillis(),
        )
    }

    // --- Structural step CRUD (optimistic + outbox, #26) ---

    suspend fun createStep(
        goalId: String,
        phaseId: String,
        title: String,
        kind: StepKind = StepKind.MANUAL,
        metric: StepMetricBinding? = null,
    ): Step {
        val stepId = UUID.randomUUID().toString()
        val siblings = stepDao.observeActive().first()
            .mapNotNull { decode(it.payloadJson, stepAdapter) }
            .count { it.phaseId == phaseId }
        val step = Step(
            stepId = stepId,
            phaseId = phaseId,
            goalId = goalId,
            title = title,
            orderIndex = siblings,
            kind = kind,
            // done/doneAt are server-derived (D9); a new step is incomplete and an
            // optimistic create NEVER asserts a completion state.
            done = false,
            doneAt = null,
            manualOverride = false,
            metric = metric,
        )
        support.createLocal(
            table = MirrorTables.GOAL_STEPS,
            id = stepCompositeId(goalId, phaseId, stepId),
            payloadJson = stepAdapter.toJson(step),
            lastUpdate = System.currentTimeMillis(),
        )
        return step
    }

    /**
     * Structural step edit (title/description/order) — NOT a done toggle. The
     * mirrored payload deliberately preserves the existing server-derived
     * `done`/`doneAt`/`manualOverride`/`metricRegressed` so the replay's PATCH body
     * (which the backend ignores for those fields) can never clobber them.
     */
    suspend fun updateStep(goalId: String, phaseId: String, step: Step) {
        support.updateLocal(
            table = MirrorTables.GOAL_STEPS,
            id = stepCompositeId(goalId, phaseId, step.stepId),
            payloadJson = stepAdapter.toJson(step),
            lastUpdate = System.currentTimeMillis(),
        )
    }

    suspend fun deleteStep(goalId: String, phaseId: String, stepId: String) {
        support.deleteLocal(
            MirrorTables.GOAL_STEPS,
            stepCompositeId(goalId, phaseId, stepId),
            System.currentTimeMillis(),
        )
    }

    // --- Step done/doneAt intents (D9, network — NEVER the outbox) ---

    /**
     * Toggle a step's done flag. Server-derived (D9): a manual toggle is an explicit
     * intent the server re-evaluates, so it goes straight to `PATCH .../steps/{sid}`
     * (NOT the structural-edit outbox) and returns the refreshed deep goal so the UI
     * sees the cascade (phase completion, next-phase activation, goal completion).
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

    /** Clear a manual override and re-run auto-evaluation for the step (intent, D9). */
    suspend fun resetStepToAuto(goalId: String, phaseId: String, stepId: String): GoalDeep {
        api.patchStep(goalId, phaseId, stepId, StepPatchRequest(resetToAuto = true))
        return refreshedDeep(goalId)
    }

    suspend fun reevaluate(goalId: String): GoalDeep {
        api.reevaluate(goalId)
        return refreshedDeep(goalId)
    }

    /** Re-fetch the deep goal after an intent and re-fan into the flat mirrors. */
    private suspend fun refreshedDeep(goalId: String): GoalDeep =
        runCatching { refreshDeep(goalId) }.getOrElse { assembleDeep(goalId) ?: throw it }

    private suspend fun appendToGoalPhaseOrder(goalId: String, phaseId: String) {
        val row = dao.getById(goalId) ?: return
        val goal = decode(row.payloadJson, goalAdapter) ?: return
        if (phaseId in goal.phaseOrder) return
        // Update the goal mirror row in place WITHOUT enqueuing (the phase CREATE
        // outbox is the server write; the goal's phaseOrder is server-derived from
        // it and reconciles on the next pull — this is a local-only display fix).
        support.refreshInto(
            MirrorTables.GOALS,
            listOf(refreshRow(goalId, goalAdapter.toJson(goal.copy(phaseOrder = goal.phaseOrder + phaseId)))),
        )
    }

    private suspend fun fillMirror() {
        val goals = api.getGoals(null)
        support.refreshInto(
            MirrorTables.GOALS,
            goals.map { refreshRow(it.goalId, goalAdapter.toJson(it)) },
        )
    }

    private fun <T> decode(json: String, adapter: com.squareup.moshi.JsonAdapter<T>): T? =
        runCatching { adapter.fromJson(json) }.getOrNull()

    private fun refreshRow(id: String, payloadJson: String) = MirrorRepositorySupport.RefreshRow(
        id = id,
        payloadJson = payloadJson,
        lastUpdate = System.currentTimeMillis(),
    )

    private fun compositeId(goalId: String, childId: String) = "$goalId/$childId"
    private fun stepCompositeId(goalId: String, phaseId: String, stepId: String) =
        "$goalId/$phaseId/$stepId"

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
