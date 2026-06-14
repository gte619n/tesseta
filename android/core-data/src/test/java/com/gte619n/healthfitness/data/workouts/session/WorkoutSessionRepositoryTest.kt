package com.gte619n.healthfitness.data.workouts.session

import com.gte619n.healthfitness.data.db.dao.WorkoutScheduledDao
import com.gte619n.healthfitness.data.db.dao.WorkoutSessionDraftDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.db.entity.WorkoutScheduledEntity
import com.gte619n.healthfitness.data.db.entity.WorkoutSessionDraftEntity
import com.gte619n.healthfitness.data.sync.DrainTrigger
import com.gte619n.healthfitness.data.sync.FakeMirrorOps
import com.gte619n.healthfitness.data.sync.FakeOutboxDao
import com.gte619n.healthfitness.data.sync.KillSwitchGate
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.sync.SyncTestMoshi
import com.gte619n.healthfitness.data.sync.fakeDeviceIdProvider
import com.gte619n.healthfitness.data.workouts.program.BlockDto
import com.gte619n.healthfitness.data.workouts.program.PrescriptionDto
import com.gte619n.healthfitness.data.workouts.program.ScheduledWorkoutDto
import com.gte619n.healthfitness.data.workouts.program.WorkoutDayDto
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramApi
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * ADR-0012 (IMPL-AND-17) — draft lifecycle, completion-through-outbox, and the
 * Decision-4 stale-draft sweep, on the pure JVM with the IMPL-AND-20 in-memory
 * sync fakes (no Room/SQLCipher/device).
 */
class WorkoutSessionRepositoryTest {

    private val moshi = SyncTestMoshi.instance
    private val scheduledAdapter = moshi.adapter(ScheduledWorkoutDto::class.java)

    private val draftDao = FakeWorkoutSessionDraftDao()
    private val scheduledDao = FakeWorkoutScheduledDao()
    private val outboxDao = FakeOutboxDao()
    private val mirror = FakeMirrorOps()
    private val api = mockk<WorkoutProgramApi>()
    private var killSwitch = false
    private var drainRequests = 0
    private var now = T0

    private val outboxRepository = OutboxRepository(
        outboxDao = outboxDao,
        mirror = mirror,
        replay = mockk(), // drain never runs in these tests
        deviceIdProvider = fakeDeviceIdProvider(),
        io = Dispatchers.Unconfined,
        clock = { now },
    )

    private val repo = WorkoutSessionRepositoryImpl(
        api = api,
        draftDao = draftDao,
        scheduledDao = scheduledDao,
        support = MirrorRepositorySupport(
            mirror = mirror,
            outbox = outboxRepository,
            killSwitch = KillSwitchGate { killSwitch },
            drainTrigger = DrainTrigger { drainRequests++ },
        ),
        outbox = outboxRepository,
        moshi = moshi,
        io = Dispatchers.Unconfined,
        clock = { now },
    )

    private fun scheduledDto() = ScheduledWorkoutDto(
        scheduledId = SCHEDULED_ID,
        date = LocalDate.parse("2026-06-08"),
        phaseId = "ph1",
        dayId = "d1",
        dayLabel = "Lower",
        status = "PLANNED",
        session = WorkoutDayDto(
            dayId = "d1",
            label = "Lower",
            dayOfWeek = DayOfWeek.MON,
            locationId = "loc1",
            blocks = listOf(
                BlockDto(
                    blockId = "b1",
                    type = "MAIN",
                    prescriptions = listOf(
                        PrescriptionDto(exerciseId = "ex1", orderIndex = 0, sets = 3),
                        PrescriptionDto(exerciseId = "ex2", orderIndex = 1, sets = 3),
                    ),
                ),
            ),
        ),
    )

    private suspend fun mirrorScheduled() {
        scheduledDao.upsert(
            WorkoutScheduledEntity(
                id = ENTITY_ID,
                payloadJson = scheduledAdapter.toJson(scheduledDto()),
                lastUpdate = T0,
                status = "ACTIVE",
                dirty = false,
                syncState = "SYNCED",
            ),
        )
    }

    private fun loggedSet(completedAt: Instant? = null) = LoggedSet(
        weightLbs = 135.0,
        reps = 8,
        rpe = 8.0,
        restSeconds = 90,
        completedAt = completedAt,
    )

    // ---- Draft lifecycle ----

    @Test
    fun `start snapshots the mirrored scheduled session`() = runTest {
        mirrorScheduled()

        val draft = repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        assertEquals("Lower", draft.scheduled.dayLabel)
        assertEquals(Instant.ofEpochMilli(T0), draft.startedAt)
        assertEquals(0, draft.totalLoggedSets)
        // Persisted: observable as the UI source of truth.
        assertNotNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
    }

    @Test
    fun `start without a mirrored session fails`() = runTest {
        val result = repo.start(PROGRAM_ID, SCHEDULED_ID)
        assertTrue(result.isFailure)
    }

    @Test
    fun `start resumes an in-flight draft instead of restarting`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        now += 60_000
        repo.updateSets(PROGRAM_ID, SCHEDULED_ID, KEY_0, listOf(loggedSet())).getOrThrow()

        now += 60_000
        val resumed = repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        // Same session: original start time, logged sets intact.
        assertEquals(Instant.ofEpochMilli(T0), resumed.startedAt)
        assertEquals(1, resumed.totalLoggedSets)
    }

    @Test
    fun `updateSets replaces one prescription's sets and bumps lastActivityAt`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        now += 120_000
        val draft = repo.updateSets(
            PROGRAM_ID, SCHEDULED_ID, KEY_0,
            listOf(loggedSet(), loggedSet().copy(reps = 6)),
        ).getOrThrow()

        assertEquals(2, draft.logged.getValue(KEY_0).size)
        assertEquals(Instant.ofEpochMilli(T0 + 120_000), draft.lastActivityAt)
        assertEquals(Instant.ofEpochMilli(T0), draft.startedAt)

        // An empty list removes the entry.
        val cleared = repo.updateSets(PROGRAM_ID, SCHEDULED_ID, KEY_0, emptyList()).getOrThrow()
        assertNull(cleared.logged[KEY_0])
    }

    @Test
    fun `discard deletes the draft and enqueues nothing`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        repo.discard(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        assertNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
        assertTrue(outboxDao.listAll().isEmpty())
    }

    // ---- Completion through the outbox ----

    @Test
    fun `finish enqueues the D2 completion and flips the mirror to PENDING COMPLETED`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        now += 600_000
        repo.updateSets(
            PROGRAM_ID, SCHEDULED_ID, KEY_0,
            listOf(loggedSet(completedAt = Instant.ofEpochMilli(now))),
        ).getOrThrow()

        now = T0 + 1_800_000 // finish 30 min after start
        repo.finish(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        // One UPDATE on the workoutScheduled mirror row, wire body = D2 request.
        val mutation = outboxDao.listAll().single()
        assertEquals(MirrorTables.WORKOUT_SCHEDULED, mutation.entityTable)
        assertEquals(ENTITY_ID, mutation.entityId)
        assertEquals(OutboxOp.UPDATE.name, mutation.op)
        val body = mutation.payloadJson!!
        assertTrue(body.contains("\"status\":\"COMPLETED\""))
        assertTrue(body.contains("\"durationSeconds\":1800"))
        assertTrue(body.contains("\"blockId\":\"b1\""))
        assertTrue(body.contains("\"rpe\":8.0"))

        // Mirror row optimistically completed (PENDING) with the sets merged
        // into their prescription, so calendars update instantly.
        val row = mirror.getRow(MirrorTables.WORKOUT_SCHEDULED, ENTITY_ID)!!
        assertTrue(row.dirty)
        assertEquals("PENDING", row.syncState)
        val mirrored = scheduledAdapter.fromJson(row.payloadJson)!!
        assertEquals("COMPLETED", mirrored.status)
        assertEquals(1800, mirrored.durationSeconds)
        val prescriptions = mirrored.session!!.blocks.single().prescriptions
        assertEquals(1, prescriptions[0].loggedSets.orEmpty().size)
        assertTrue(prescriptions[1].loggedSets.orEmpty().isEmpty())

        // Draft gone; a drain was requested.
        assertNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
        assertEquals(1, drainRequests)
    }

    @Test
    fun `finish without a draft fails`() = runTest {
        mirrorScheduled()
        assertTrue(repo.finish(PROGRAM_ID, SCHEDULED_ID).isFailure)
    }

    @Test
    fun `skip enqueues SKIPPED with no actuals and clears the mirror's logged sets`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        repo.updateSets(PROGRAM_ID, SCHEDULED_ID, KEY_0, listOf(loggedSet())).getOrThrow()

        repo.skip(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        val mutation = outboxDao.listAll().single()
        assertEquals(OutboxOp.UPDATE.name, mutation.op)
        val body = mutation.payloadJson!!
        assertTrue(body.contains("\"status\":\"SKIPPED\""))
        assertTrue(!body.contains("completedAt"))
        assertTrue(!body.contains("durationSeconds"))

        val mirrored = scheduledAdapter
            .fromJson(mirror.getRow(MirrorTables.WORKOUT_SCHEDULED, ENTITY_ID)!!.payloadJson)!!
        assertEquals("SKIPPED", mirrored.status)
        assertNull(mirrored.completedAt)
        assertTrue(
            mirrored.session!!.blocks.single().prescriptions.all { it.loggedSets.orEmpty().isEmpty() },
        )
        assertNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
    }

    @Test
    fun `skip works without a draft (declining today's session)`() = runTest {
        mirrorScheduled()

        repo.skip(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        assertTrue(outboxDao.listAll().single().payloadJson!!.contains("\"status\":\"SKIPPED\""))
    }

    @Test
    fun `kill-switch routes the completion to a direct live PUT`() = runTest {
        // D13: live-network mode — neither Room nor the outbox is authoritative.
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        killSwitch = true
        coEvery { api.completeSession(any(), any(), any()) } returns scheduledDto()

        repo.finish(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        coVerify(exactly = 1) { api.completeSession(PROGRAM_ID, SCHEDULED_ID, any()) }
        assertTrue(outboxDao.listAll().isEmpty())
    }

    // ---- ADR-0012 Decision 4: stale-draft auto-finalize ----

    @Test
    fun `stale draft with zero sets is discarded with no upload`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        now = T0 + STALE + 1
        val result = repo.finalizeStaleDrafts().getOrThrow()

        assertEquals(0, result.finalized)
        assertEquals(1, result.discarded)
        assertNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
        assertTrue(outboxDao.listAll().isEmpty())
    }

    @Test
    fun `stale draft with sets finalizes COMPLETED with duration to the last set`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        // Two sets; the LAST one completed 40 min after start.
        repo.updateSets(
            PROGRAM_ID, SCHEDULED_ID, KEY_0,
            listOf(
                loggedSet(completedAt = Instant.ofEpochMilli(T0 + 1_200_000)),
                loggedSet(completedAt = Instant.ofEpochMilli(T0 + 2_400_000)),
            ),
        ).getOrThrow()

        now = T0 + STALE + 60_000
        val result = repo.finalizeStaleDrafts().getOrThrow()

        assertEquals(1, result.finalized)
        assertEquals(0, result.discarded)
        val body = outboxDao.listAll().single().payloadJson!!
        assertTrue(body.contains("\"status\":\"COMPLETED\""))
        // Duration = start → last set's completedAt (2400s), NOT start → now.
        assertTrue(body.contains("\"durationSeconds\":2400"))
        assertTrue(body.contains("\"completedAt\":\"${Instant.ofEpochMilli(T0 + 2_400_000)}\""))
        assertNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
    }

    @Test
    fun `sets without timestamps finalize at the draft's last activity`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        now = T0 + 900_000
        repo.updateSets(PROGRAM_ID, SCHEDULED_ID, KEY_0, listOf(loggedSet())).getOrThrow()

        now = T0 + STALE + 1_000_000
        repo.finalizeStaleDrafts().getOrThrow()

        val body = outboxDao.listAll().single().payloadJson!!
        assertTrue(body.contains("\"durationSeconds\":900"))
    }

    @Test
    fun `draft idle exactly 24h is not yet stale`() = runTest {
        // The cutoff is strict (> 24h): an exactly-24h-idle draft survives
        // until the next sweep.
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        now = T0 + STALE
        val result = repo.finalizeStaleDrafts().getOrThrow()

        assertEquals(0, result.finalized)
        assertEquals(0, result.discarded)
        assertNotNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
    }

    @Test
    fun `activity resets the staleness clock`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        now = T0 + STALE - 60_000
        repo.updateSets(PROGRAM_ID, SCHEDULED_ID, KEY_0, listOf(loggedSet())).getOrThrow()

        now = T0 + STALE + 60_000 // >24h since start, <24h since last set
        val result = repo.finalizeStaleDrafts().getOrThrow()

        assertEquals(0, result.finalized + result.discarded)
        assertNotNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
    }

    // ---- IMPL-17 Q3: parked-completion detection + restore-into-logger ----

    /** Park the newest outbox row the way the drain parks a terminal 4xx (A10). */
    private suspend fun parkLatestMutation() {
        val row = outboxDao.listAll().last()
        outboxDao.recordFailure(
            mutationId = row.mutationId,
            attempts = 1,
            nextAttemptAt = OutboxRepository.PARKED_NEXT_ATTEMPT,
        )
    }

    /**
     * In production the optimistic mirror write and [scheduledDao] hit the SAME
     * Room table; this fixture splits them into two fakes, so restore tests
     * first reflect the optimistic mirror row back into the dao.
     */
    private suspend fun reflectMirrorIntoScheduledDao() {
        val row = mirror.getRow(MirrorTables.WORKOUT_SCHEDULED, ENTITY_ID)!!
        scheduledDao.upsert(
            WorkoutScheduledEntity(
                id = row.id,
                payloadJson = row.payloadJson,
                lastUpdate = row.lastUpdate,
                status = row.status,
                dirty = row.dirty,
                syncState = row.syncState,
            ),
        )
    }

    /** Start, log two timestamped sets, finish 30 min in, and park the upload. */
    private suspend fun finishAndPark() {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        repo.updateSets(
            PROGRAM_ID, SCHEDULED_ID, KEY_0,
            listOf(
                loggedSet(completedAt = Instant.ofEpochMilli(T0 + 600_000)),
                loggedSet(completedAt = Instant.ofEpochMilli(T0 + 1_200_000)).copy(reps = 6),
            ),
        ).getOrThrow()
        now = T0 + 1_800_000 // finish 30 min after start
        repo.finish(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        parkLatestMutation()
    }

    @Test
    fun `parked completion surfaces with counts from the wire payload`() = runTest {
        finishAndPark()

        val parked = repo.observeParkedCompletions().first().single()

        assertEquals(PROGRAM_ID, parked.programId)
        assertEquals(SCHEDULED_ID, parked.scheduledId)
        assertEquals(ScheduledStatus.COMPLETED, parked.status)
        assertEquals(Instant.ofEpochMilli(T0 + 1_800_000), parked.completedAt)
        assertEquals(2, parked.loggedSetCount)
        assertEquals(0, parked.orphanedSetCount)
        assertTrue(parked.sessionAvailable)
        assertEquals("Lower", parked.dayLabel)
    }

    @Test
    fun `mutation merely in backoff is not a parked completion`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        repo.finish(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        val row = outboxDao.listAll().single()
        outboxDao.recordFailure(row.mutationId, attempts = 1, nextAttemptAt = now + 30_000)

        assertTrue(repo.observeParkedCompletions().first().isEmpty())
    }

    @Test
    fun `parked completion is suppressed while a draft is in flight for the session`() = runTest {
        finishAndPark()
        // The user started the session again: the new draft owns the session
        // (N4) and its finish supersedes the parked payload in the chain.
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        assertTrue(repo.observeParkedCompletions().first().isEmpty())
    }

    @Test
    fun `orphaned sets are counted against the CURRENT snapshot`() = runTest {
        finishAndPark()
        // Re-activation rewrote the plan: block b1 is gone.
        scheduledDao.upsert(
            WorkoutScheduledEntity(
                id = ENTITY_ID,
                payloadJson = scheduledAdapter.toJson(rewrittenDto()),
                lastUpdate = T0 + 2_000_000,
                status = "ACTIVE",
                dirty = false,
                syncState = "SYNCED",
            ),
        )

        val parked = repo.observeParkedCompletions().first().single()

        assertEquals(2, parked.loggedSetCount)
        assertEquals(2, parked.orphanedSetCount)
        assertTrue(parked.sessionAvailable)
    }

    @Test
    fun `missing mirror row surfaces as session-unavailable`() = runTest {
        finishAndPark()
        scheduledDao.delete(ENTITY_ID)

        val parked = repo.observeParkedCompletions().first().single()

        assertEquals(false, parked.sessionAvailable)
        assertEquals(2, parked.orphanedSetCount)
    }

    @Test
    fun `restore re-materializes the draft and deletes the parked row`() = runTest {
        finishAndPark()
        reflectMirrorIntoScheduledDao() // optimistic COMPLETED row, dirty+FAILED-able

        now = T0 + 90_000_000 // much later: the user finally notices the banner
        val draft = repo.restoreParked(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        // startedAt derives from the wire outcome: completedAt − durationSeconds.
        assertEquals(Instant.ofEpochMilli(T0), draft.startedAt)
        // …but the stale window restarts now, so the sweep can't instantly
        // re-finalize a days-old restored session.
        assertEquals(Instant.ofEpochMilli(now), draft.lastActivityAt)
        // Matched sets land on their (blockId, orderIndex) prescription.
        assertEquals(2, draft.logged.getValue(KEY_0).size)
        assertEquals(6, draft.logged.getValue(KEY_0)[1].reps)
        // The draft is the single owner again (N4): the parked row is gone…
        assertTrue(outboxDao.listAll().isEmpty())
        assertNotNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
        // …and the optimistic local completion is reverted to a clean planned
        // row so the next pull reconciles with the server's canonical state.
        val reverted = scheduledDao.getById(ENTITY_ID)!!
        assertEquals(false, reverted.dirty)
        assertEquals("SYNCED", reverted.syncState)
        val dto = scheduledAdapter.fromJson(reverted.payloadJson)!!
        assertEquals("PLANNED", dto.status)
        assertNull(dto.completedAt)
        assertTrue(dto.session!!.blocks.single().prescriptions.all { it.loggedSets.orEmpty().isEmpty() })
        // The draft snapshot is the cleared current snapshot too.
        assertEquals(ScheduledStatus.PLANNED, draft.scheduled.status)
    }

    @Test
    fun `restore drops orphaned entries but keeps matched ones`() = runTest {
        mirrorScheduled()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        repo.updateSets(
            PROGRAM_ID, SCHEDULED_ID, KEY_0,
            listOf(loggedSet(completedAt = Instant.ofEpochMilli(T0 + 600_000))),
        ).getOrThrow()
        repo.updateSets(
            PROGRAM_ID, SCHEDULED_ID, KEY_1,
            listOf(loggedSet(completedAt = Instant.ofEpochMilli(T0 + 900_000))),
        ).getOrThrow()
        now = T0 + 1_800_000
        repo.finish(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        parkLatestMutation()
        // The rewritten plan kept b1/0 but dropped b1/1.
        scheduledDao.upsert(
            WorkoutScheduledEntity(
                id = ENTITY_ID,
                payloadJson = scheduledAdapter.toJson(
                    scheduledDto().let { dto ->
                        dto.copy(
                            session = dto.session!!.copy(
                                blocks = listOf(
                                    dto.session!!.blocks.single().copy(
                                        prescriptions = dto.session!!.blocks.single()
                                            .prescriptions.take(1),
                                    ),
                                ),
                            ),
                        )
                    },
                ),
                lastUpdate = T0 + 2_000_000,
                status = "ACTIVE",
                dirty = false,
                syncState = "SYNCED",
            ),
        )

        val parked = repo.observeParkedCompletions().first().single()
        assertEquals(1, parked.orphanedSetCount)

        val draft = repo.restoreParked(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        assertEquals(1, draft.logged.getValue(KEY_0).size)
        assertNull(draft.logged[KEY_1])
        assertTrue(outboxDao.listAll().isEmpty())
    }

    @Test
    fun `restore fails when the session is no longer mirrored and keeps the parked row`() = runTest {
        finishAndPark()
        scheduledDao.delete(ENTITY_ID)

        val result = repo.restoreParked(PROGRAM_ID, SCHEDULED_ID)

        assertTrue(result.isFailure)
        // Nothing was lost: the parked row (and its payload) survive.
        assertEquals(1, outboxDao.listAll().size)
        assertNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
    }

    @Test
    fun `restore refuses to create a second owner beside an in-flight draft`() = runTest {
        finishAndPark()
        repo.start(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        val inFlight = draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID)

        val result = repo.restoreParked(PROGRAM_ID, SCHEDULED_ID)

        assertTrue(result.isFailure)
        // The existing draft is untouched and the parked row survives.
        assertEquals(inFlight, draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
        assertEquals(1, outboxDao.listAll().size)
    }

    @Test
    fun `restoring a parked SKIPPED outcome yields an empty draft started now`() = runTest {
        mirrorScheduled()
        repo.skip(PROGRAM_ID, SCHEDULED_ID).getOrThrow()
        parkLatestMutation()

        now = T0 + 5_000_000
        val draft = repo.restoreParked(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        // No completedAt/duration/set timestamps to derive from ⇒ started now.
        assertEquals(Instant.ofEpochMilli(now), draft.startedAt)
        assertEquals(0, draft.totalLoggedSets)
        assertTrue(outboxDao.listAll().isEmpty())
    }

    @Test
    fun `discardParked deletes the row and reverts the optimistic completion`() = runTest {
        finishAndPark()
        reflectMirrorIntoScheduledDao()

        repo.discardParked(PROGRAM_ID, SCHEDULED_ID).getOrThrow()

        assertTrue(outboxDao.listAll().isEmpty())
        assertNull(draftDao.getByKey(PROGRAM_ID, SCHEDULED_ID))
        val reverted = scheduledDao.getById(ENTITY_ID)!!
        assertEquals(false, reverted.dirty)
        assertEquals("PLANNED", scheduledAdapter.fromJson(reverted.payloadJson)!!.status)
        assertTrue(repo.observeParkedCompletions().first().isEmpty())
    }

    /** The plan rewritten under the upload: block b1 replaced wholesale by b9. */
    private fun rewrittenDto(): ScheduledWorkoutDto {
        val dto = scheduledDto()
        return dto.copy(
            session = dto.session!!.copy(
                blocks = listOf(
                    BlockDto(
                        blockId = "b9",
                        type = "MAIN",
                        prescriptions = listOf(
                            PrescriptionDto(exerciseId = "ex9", orderIndex = 0, sets = 5),
                        ),
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val PROGRAM_ID = "prog-1"
        const val SCHEDULED_ID = "2026-06-08_d1"
        const val ENTITY_ID = "$PROGRAM_ID/$SCHEDULED_ID"
        const val T0 = 1_000_000L
        const val STALE = WorkoutSessionRepositoryImpl.STALE_AFTER_MILLIS
        val KEY_0 = PrescriptionKey(blockId = "b1", orderIndex = 0)
        val KEY_1 = PrescriptionKey(blockId = "b1", orderIndex = 1)
    }
}

/** In-memory [WorkoutSessionDraftDao] keyed by (programId, scheduledId). */
private class FakeWorkoutSessionDraftDao : WorkoutSessionDraftDao {
    private val rows =
        MutableStateFlow<Map<Pair<String, String>, WorkoutSessionDraftEntity>>(emptyMap())

    override fun observe(programId: String, scheduledId: String): Flow<WorkoutSessionDraftEntity?> =
        rows.map { it[programId to scheduledId] }

    override fun observeAll(): Flow<List<WorkoutSessionDraftEntity>> =
        rows.map { all -> all.values.sortedByDescending { it.startedAt } }

    override suspend fun getByKey(programId: String, scheduledId: String) =
        rows.value[programId to scheduledId]

    override suspend fun listIdleBefore(cutoff: Long): List<WorkoutSessionDraftEntity> =
        rows.value.values.filter { it.lastActivityAt < cutoff }

    override suspend fun upsert(row: WorkoutSessionDraftEntity) {
        rows.value = rows.value + ((row.programId to row.scheduledId) to row)
    }

    override suspend fun delete(programId: String, scheduledId: String) {
        rows.value = rows.value - (programId to scheduledId)
    }

    override suspend fun clear() {
        rows.value = emptyMap()
    }
}

/** In-memory [WorkoutScheduledDao] (only the members the repository touches matter). */
private class FakeWorkoutScheduledDao : WorkoutScheduledDao {
    private val rows = MutableStateFlow<Map<String, WorkoutScheduledEntity>>(emptyMap())

    override fun observeActive(): Flow<List<WorkoutScheduledEntity>> =
        rows.map { all -> all.values.filter { it.status != "ARCHIVED" } }

    override suspend fun getById(id: String): WorkoutScheduledEntity? = rows.value[id]

    override suspend fun upsert(row: WorkoutScheduledEntity) {
        rows.value = rows.value + (row.id to row)
    }

    override suspend fun upsertAll(rows: List<WorkoutScheduledEntity>) {
        rows.forEach { upsert(it) }
    }

    override suspend fun markArchived(id: String, lastUpdate: Long) {
        rows.value[id]?.let {
            rows.value = rows.value + (id to it.copy(status = "ARCHIVED", lastUpdate = lastUpdate))
        }
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value - id
    }
}
