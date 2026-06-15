package com.gte619n.healthfitness.data.goals

import com.gte619n.healthfitness.data.db.dao.GoalDao
import com.gte619n.healthfitness.data.db.dao.GoalPhaseDao
import com.gte619n.healthfitness.data.db.dao.GoalStepDao
import com.gte619n.healthfitness.data.db.entity.GoalEntity
import com.gte619n.healthfitness.data.db.entity.GoalPhaseEntity
import com.gte619n.healthfitness.data.db.entity.GoalStepEntity
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.sync.DrainTrigger
import com.gte619n.healthfitness.data.sync.FakeMirrorOps
import com.gte619n.healthfitness.data.sync.FakeOutboxDao
import com.gte619n.healthfitness.data.sync.KillSwitchGate
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.sync.MirrorRowData
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.sync.SyncTestMoshi
import com.gte619n.healthfitness.data.sync.fakeDeviceIdProvider
import com.gte619n.healthfitness.domain.goals.Goal
import com.gte619n.healthfitness.domain.goals.GoalDomain
import com.gte619n.healthfitness.domain.goals.GoalSource
import com.gte619n.healthfitness.domain.goals.GoalStatus
import com.gte619n.healthfitness.domain.goals.Phase
import com.gte619n.healthfitness.domain.goals.PhaseStatus
import com.gte619n.healthfitness.domain.goals.Step
import com.gte619n.healthfitness.domain.goals.StepKind
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * IMPL-AND-20 (#26) — offline goals nested-aggregate editing.
 *
 * Asserts the roadmap assembles from the flat mirror tables and that a phase/step
 * edit is optimistic + outbox (PENDING), with done/doneAt left untouched.
 */
class GoalsRepositoryTest {

    private lateinit var repository: GoalsRepository
    private lateinit var mirror: FakeMirrorOps
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var goalDao: FakeGoalDao
    private lateinit var phaseDao: FakeGoalPhaseDao
    private lateinit var stepDao: FakeGoalStepDao
    private val api = mockk<GoalsApi>(relaxed = true)

    private val goalAdapter = SyncTestMoshi.instance.adapter(Goal::class.java)
    private val phaseAdapter = SyncTestMoshi.instance.adapter(Phase::class.java)
    private val stepAdapter = SyncTestMoshi.instance.adapter(Step::class.java)

    @Before
    fun setUp() {
        mirror = FakeMirrorOps()
        outboxDao = FakeOutboxDao()
        goalDao = FakeGoalDao(mirror)
        phaseDao = FakeGoalPhaseDao(mirror)
        stepDao = FakeGoalStepDao(mirror)
        val outbox = OutboxRepository(
            outboxDao = outboxDao,
            mirror = mirror,
            replay = mockk(relaxed = true),
            deviceIdProvider = fakeDeviceIdProvider("device-A"),
            diagnostics = com.gte619n.healthfitness.data.sync.SyncDiagnostics(),
            io = Dispatchers.Unconfined,
            clock = { 1_000L },
        )
        val support = MirrorRepositorySupport(
            mirror = mirror,
            outbox = outbox,
            killSwitch = KillSwitchGate { false },
            drainTrigger = DrainTrigger { },
        )
        repository = GoalsRepository(api, goalDao, phaseDao, stepDao, support, SyncTestMoshi.instance)
    }

    private fun seedGoal() = runBlocking {
        val goal = Goal(
            goalId = "g1", title = "Run 5k", domain = GoalDomain.CARDIOVASCULAR,
            status = GoalStatus.ACTIVE, source = GoalSource.MANUAL, phaseOrder = listOf("p1"),
        )
        mirror.upsert(
            MirrorTables.GOALS,
            MirrorRowData("g1", goalAdapter.toJson(goal), 1L, "ACTIVE", false, "SYNCED"),
        )
        val phase = Phase(
            phaseId = "p1", goalId = "g1", title = "Base", orderIndex = 0,
            status = PhaseStatus.ACTIVE, stepOrder = listOf("s1"),
        )
        mirror.upsert(
            MirrorTables.GOAL_PHASES,
            MirrorRowData("g1/p1", phaseAdapter.toJson(phase), 1L, "ACTIVE", false, "SYNCED"),
        )
        val step = Step(
            stepId = "s1", phaseId = "p1", goalId = "g1", title = "Walk 1mi",
            orderIndex = 0, kind = StepKind.MANUAL, done = true, doneAt = "2026-05-01T00:00:00Z",
        )
        mirror.upsert(
            MirrorTables.GOAL_STEPS,
            MirrorRowData("g1/p1/s1", stepAdapter.toJson(step), 1L, "ACTIVE", false, "SYNCED"),
        )
    }

    @Test
    fun `goalDeep assembles the roadmap from the flat mirror tables offline`() = runBlocking {
        seedGoal()
        // The live refresh fails (offline) → assemble from the mirror.
        io.mockk.coEvery { api.getGoalDeep("g1") } throws RuntimeException("offline")

        val deep = repository.goalDeep("g1")
        assertEquals("Run 5k", deep.title)
        assertEquals(1, deep.phases.size)
        val phase = deep.phases.single()
        assertEquals("Base", phase.title)
        assertEquals(1, phase.steps.size)
        assertEquals("Walk 1mi", phase.steps.single().title)
        // Server-derived done/doneAt survive the assembly untouched.
        assertTrue(phase.steps.single().done)
        assertNotNull(phase.steps.single().doneAt)
    }

    @Test
    fun `offline createPhase shows PENDING and enqueues a CREATE`() = runBlocking {
        seedGoal()
        val phase = repository.createPhase("g1", title = "Build", description = "ramp up")

        val row = mirror.getRow(MirrorTables.GOAL_PHASES, "g1/${phase.phaseId}")!!
        assertTrue(row.dirty)
        assertEquals("PENDING", row.syncState)
        val queued = outboxDao.listByEntity("g1/${phase.phaseId}").single()
        assertEquals(OutboxOp.CREATE.name, queued.op)
        assertEquals(MirrorTables.GOAL_PHASES, queued.entityTable)
    }

    @Test
    fun `offline updateStep keeps server-derived done out of the edit and shows PENDING`() = runBlocking {
        seedGoal()
        // Edit only the title; the existing done=true must be carried verbatim.
        val edited = Step(
            stepId = "s1", phaseId = "p1", goalId = "g1", title = "Walk 2mi",
            orderIndex = 0, kind = StepKind.MANUAL, done = true, doneAt = "2026-05-01T00:00:00Z",
        )
        repository.updateStep("g1", "p1", edited)

        val row = mirror.getRow(MirrorTables.GOAL_STEPS, "g1/p1/s1")!!
        assertEquals("PENDING", row.syncState)
        assertTrue(row.dirty)
        val queued = outboxDao.listByEntity("g1/p1/s1").single()
        assertEquals(OutboxOp.UPDATE.name, queued.op)
        // The payload preserves the server-derived done flag (never clobbered).
        assertTrue(row.payloadJson.contains("\"done\":true"))
    }
}

// --- In-memory DAOs over a shared FakeMirrorOps ---

private class FakeGoalDao(private val mirror: FakeMirrorOps) : GoalDao {
    private fun rows() = mirror.rows.entries
        .filter { it.key.startsWith("${MirrorTables.GOALS}:") }
        .map { it.value }
        .map { GoalEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }
    override fun observeActive(): Flow<List<GoalEntity>> =
        MutableStateFlow(rows().filter { it.status != "ARCHIVED" }.sortedByDescending { it.lastUpdate })
    override suspend fun getById(id: String): GoalEntity? = rows().firstOrNull { it.id == id }
    override suspend fun upsert(row: GoalEntity) =
        mirror.upsert(MirrorTables.GOALS, MirrorRowData(row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState))
    override suspend fun upsertAll(rows: List<GoalEntity>) { rows.forEach { upsert(it) } }
    override suspend fun markArchived(id: String, lastUpdate: Long) = mirror.markArchived(MirrorTables.GOALS, id, lastUpdate)
    override suspend fun delete(id: String) = mirror.delete(MirrorTables.GOALS, id)
}

private class FakeGoalPhaseDao(private val mirror: FakeMirrorOps) : GoalPhaseDao {
    private fun rows() = mirror.rows.entries
        .filter { it.key.startsWith("${MirrorTables.GOAL_PHASES}:") }
        .map { it.value }
        .map { GoalPhaseEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }
    override fun observeActive(): Flow<List<GoalPhaseEntity>> =
        MutableStateFlow(rows().filter { it.status != "ARCHIVED" }.sortedByDescending { it.lastUpdate })
    override suspend fun getById(id: String): GoalPhaseEntity? = rows().firstOrNull { it.id == id }
    override suspend fun upsert(row: GoalPhaseEntity) =
        mirror.upsert(MirrorTables.GOAL_PHASES, MirrorRowData(row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState))
    override suspend fun upsertAll(rows: List<GoalPhaseEntity>) { rows.forEach { upsert(it) } }
    override suspend fun markArchived(id: String, lastUpdate: Long) = mirror.markArchived(MirrorTables.GOAL_PHASES, id, lastUpdate)
    override suspend fun delete(id: String) = mirror.delete(MirrorTables.GOAL_PHASES, id)
}

private class FakeGoalStepDao(private val mirror: FakeMirrorOps) : GoalStepDao {
    private fun rows() = mirror.rows.entries
        .filter { it.key.startsWith("${MirrorTables.GOAL_STEPS}:") }
        .map { it.value }
        .map { GoalStepEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }
    override fun observeActive(): Flow<List<GoalStepEntity>> =
        MutableStateFlow(rows().filter { it.status != "ARCHIVED" }.sortedByDescending { it.lastUpdate })
    override suspend fun getById(id: String): GoalStepEntity? = rows().firstOrNull { it.id == id }
    override suspend fun upsert(row: GoalStepEntity) =
        mirror.upsert(MirrorTables.GOAL_STEPS, MirrorRowData(row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState))
    override suspend fun upsertAll(rows: List<GoalStepEntity>) { rows.forEach { upsert(it) } }
    override suspend fun markArchived(id: String, lastUpdate: Long) = mirror.markArchived(MirrorTables.GOAL_STEPS, id, lastUpdate)
    override suspend fun delete(id: String) = mirror.delete(MirrorTables.GOAL_STEPS, id)
}
