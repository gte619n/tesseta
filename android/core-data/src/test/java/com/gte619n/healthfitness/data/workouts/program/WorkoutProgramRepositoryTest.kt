package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.data.db.dao.WorkoutProgramDao
import com.gte619n.healthfitness.data.db.dao.WorkoutScheduledDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.WorkoutProgramEntity
import com.gte619n.healthfitness.data.db.entity.WorkoutScheduledEntity
import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Offline-first contract for the deep workout-program read. The mirror may hold
 * a shallow list row (no phases) or a raw delta doc (phases but no embedded
 * exercise summaries); [WorkoutProgramRepositoryImpl.get] must only trust a
 * complete assembled deep tree and otherwise self-heal from the network,
 * persisting it for offline — while still serving a cached doc when offline.
 */
class WorkoutProgramRepositoryTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(LocalDateAdapter())
        .add(InstantAdapter())
        .add(DayOfWeekMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val deepAdapter = moshi.adapter(WorkoutProgramDeepDto::class.java)
    private val listAdapter = moshi.adapter(WorkoutProgramDto::class.java)
    private val scheduledAdapter = moshi.adapter(ScheduledWorkoutDto::class.java)

    private val api: WorkoutProgramApi = mockk()
    private val programDao: WorkoutProgramDao = mockk(relaxed = true)
    private val scheduledDao: WorkoutScheduledDao = mockk(relaxed = true)
    private val support: MirrorRepositorySupport = mockk(relaxed = true)

    private lateinit var repo: WorkoutProgramRepositoryImpl

    private val now = Instant.parse("2026-05-01T00:00:00Z")

    @Before
    fun setUp() {
        repo = WorkoutProgramRepositoryImpl(api, programDao, scheduledDao, support, moshi)
        coEvery { support.killSwitchOn() } returns false
        // Default: no cached schedule (backfill is a no-op). Overridden per test.
        coEvery { scheduledDao.observeActive() } returns flowOf(emptyList())
    }

    private fun entity(id: String, json: String) =
        WorkoutProgramEntity(id, json, now.toEpochMilli(), "ACTIVE", false, "SYNCED")

    /** A complete assembled deep program: phases present, prescription has exercise. */
    private fun assembledDeep(id: String = "p1") = WorkoutProgramDeepDto(
        programId = id,
        title = "Strength",
        status = "ACTIVE",
        source = "AI_GENERATED",
        createdAt = now,
        updatedAt = now,
        phases = listOf(
            PhaseDto(
                phaseId = "ph1",
                status = "ACTIVE",
                days = listOf(
                    WorkoutDayDto(
                        dayId = "d1",
                        dayOfWeek = DayOfWeek.MON,
                        blocks = listOf(
                            BlockDto(
                                blockId = "b1",
                                type = "MAIN",
                                prescriptions = listOf(
                                    PrescriptionDto(
                                        exerciseId = "ex1",
                                        sets = 3,
                                        exercise = ExerciseSummaryDto("ex1", "Back Squat"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** A raw delta doc: phases present but the prescription has no exercise summary. */
    private fun rawDeep(id: String = "p1") = assembledDeep(id).let { deep ->
        deep.copy(
            phases = deep.phases.map { ph ->
                ph.copy(
                    days = ph.days.map { d ->
                        d.copy(
                            blocks = d.blocks.map { b ->
                                b.copy(prescriptions = b.prescriptions.map { it.copy(exercise = null) })
                            },
                        )
                    },
                )
            },
        )
    }

    @Test
    fun `workoutHistory maps the completed sessions from the network`() = runBlocking {
        coEvery { api.workoutHistory(any(), any()) } returns WorkoutHistoryPageDto(
            items = listOf(
                ScheduledWorkoutDto(
                    scheduledId = "2026-06-20_d1",
                    date = java.time.LocalDate.parse("2026-06-20"),
                    dayLabel = "Upper A",
                    status = "COMPLETED",
                    durationSeconds = 2_840,
                ),
            ),
            hasMore = false,
        )

        val result = repo.workoutHistory().getOrThrow()

        assertEquals(1, result.size)
        assertEquals("Upper A", result[0].dayLabel)
        assertEquals(
            com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus.COMPLETED,
            result[0].status,
        )
        coVerify(exactly = 1) { api.workoutHistory(any(), any()) }
    }

    @Test
    fun `workoutHistory walks every page until hasMore is false`() = runBlocking {
        coEvery { api.workoutHistory(page = 0, size = any()) } returns WorkoutHistoryPageDto(
            items = listOf(
                ScheduledWorkoutDto(
                    scheduledId = "p0",
                    date = java.time.LocalDate.parse("2026-06-20"),
                    dayLabel = "Page 0",
                    status = "COMPLETED",
                ),
            ),
            hasMore = true,
        )
        coEvery { api.workoutHistory(page = 1, size = any()) } returns WorkoutHistoryPageDto(
            items = listOf(
                ScheduledWorkoutDto(
                    scheduledId = "p1",
                    date = java.time.LocalDate.parse("2026-06-19"),
                    dayLabel = "Page 1",
                    status = "COMPLETED",
                ),
            ),
            hasMore = false,
        )

        val result = repo.workoutHistory().getOrThrow()

        assertEquals(2, result.size)
        assertEquals(listOf("Page 0", "Page 1"), result.map { it.dayLabel })
        coVerify(exactly = 1) { api.workoutHistory(page = 0, size = any()) }
        coVerify(exactly = 1) { api.workoutHistory(page = 1, size = any()) }
    }

    @Test
    fun `get refreshes from network when mirror holds a shallow row`() = runBlocking {
        val shallow = listAdapter.toJson(
            WorkoutProgramDto(
                programId = "p1", title = "Strength", status = "ACTIVE",
                source = "AI_GENERATED", createdAt = now, updatedAt = now,
            ),
        )
        coEvery { programDao.getById("p1") } returns entity("p1", shallow)
        coEvery { api.get("p1") } returns assembledDeep()

        val program = repo.get("p1").getOrThrow()

        // The shallow row decodes to empty phases, so the screen would be blank;
        // the repo must fetch the assembled deep instead.
        assertEquals(1, program.phases.size)
        val rx = program.phases[0].days[0].blocks[0].prescriptions[0]
        assertNotNull(rx.exercise)
        assertEquals("Back Squat", rx.exercise?.name)
        coVerify(exactly = 1) { api.get("p1") }
        // and persist it for offline
        coVerify(exactly = 1) { support.refreshInto(MirrorTables.WORKOUT_PROGRAMS, any()) }
    }

    @Test
    fun `get serves the mirror without network when deep tree is complete`() = runBlocking {
        coEvery { programDao.getById("p1") } returns entity("p1", deepAdapter.toJson(assembledDeep()))

        val program = repo.get("p1").getOrThrow()

        assertEquals(1, program.phases.size)
        assertEquals("Back Squat", program.phases[0].days[0].blocks[0].prescriptions[0].exercise?.name)
        coVerify(exactly = 0) { api.get(any()) }
    }

    @Test
    fun `get falls back to cached doc when offline and tree is incomplete`() = runBlocking {
        coEvery { programDao.getById("p1") } returns entity("p1", deepAdapter.toJson(rawDeep()))
        coEvery { api.get("p1") } throws IOException("offline")

        val program = repo.get("p1").getOrThrow()

        // Best-effort: the raw doc still has the structure, so show it (exercise
        // summary just isn't available offline) rather than failing the screen.
        assertEquals(1, program.phases.size)
        assertNull(program.phases[0].days[0].blocks[0].prescriptions[0].exercise)
    }

    @Test
    fun `get surfaces the error when offline and nothing is cached`() = runBlocking {
        coEvery { programDao.getById("p1") } returns null
        coEvery { api.get("p1") } throws IOException("offline")

        val result = repo.get("p1")

        assertEquals(true, result.isFailure)
    }

    @Test
    fun `get backfills session-only phases from the cached schedule offline`() = runBlocking {
        // Imported-history shape: phases present, but template days are empty —
        // the workouts live in the schedule.
        val sessionOnly = WorkoutProgramDeepDto(
            programId = "p1", title = "Imported", status = "COMPLETED",
            source = "MANUAL", createdAt = now, updatedAt = now,
            phases = listOf(PhaseDto(phaseId = "ph1", status = "COMPLETED", days = emptyList())),
        )
        coEvery { programDao.getById("p1") } returns entity("p1", deepAdapter.toJson(sessionOnly))
        coEvery { api.get("p1") } throws IOException("offline")

        val session = ScheduledWorkoutDto(
            scheduledId = "2026-01-02_s0",
            date = java.time.LocalDate.parse("2026-01-02"),
            phaseId = "ph1", dayId = "s0", dayLabel = "Push", status = "COMPLETED",
            session = WorkoutDayDto(
                dayId = "s0", label = "Push", dayOfWeek = DayOfWeek.MON,
                blocks = listOf(
                    BlockDto(
                        blockId = "logged", type = "MAIN",
                        prescriptions = listOf(PrescriptionDto(exerciseId = "ex1", notes = "Bench Press")),
                    ),
                ),
            ),
        )
        coEvery { scheduledDao.observeActive() } returns flowOf(
            listOf(
                WorkoutScheduledEntity(
                    "p1/2026-01-02_s0", scheduledAdapter.toJson(session),
                    now.toEpochMilli(), "COMPLETED", false, "SYNCED",
                ),
            ),
        )

        val program = repo.get("p1").getOrThrow()

        // The empty-day phase is filled from the cached session, so the day and
        // its logged exercise show up offline.
        assertEquals(1, program.phases[0].days.size)
        assertEquals("Push", program.phases[0].days[0].label)
        assertEquals(
            "Bench Press",
            program.phases[0].days[0].blocks[0].prescriptions[0].notes,
        )
    }
}
