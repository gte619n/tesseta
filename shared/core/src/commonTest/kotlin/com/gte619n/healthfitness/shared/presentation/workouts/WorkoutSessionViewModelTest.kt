package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.data.WorkoutSessionRepository
import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import com.gte619n.healthfitness.shared.domain.workouts.program.Block
import com.gte619n.healthfitness.shared.domain.workouts.program.BlockType
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.Prescription
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.shared.domain.workouts.session.DraftStatus
import com.gte619n.healthfitness.shared.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.shared.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave D(ii) — shared active-session VM tests. Verifies:
 *  1. logging a set persists to the draft (Room contract) and re-emits;
 *  2. the rest countdown is a SINGLE self-ticking source that decrements once per
 *     wall-clock second (the rest-timer-dual-state-gate anti-drift check);
 *  3. finishing aggregates all logged sets into the completion upload;
 *  4. completing the last set auto-opens the finish summary AND clears the timer
 *     (no dangling rest after the final set).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val key = PrescriptionKey(blockId = "b1", orderIndex = 0)

    // A 2-set, 90s-rest single-exercise session.
    private fun prescription(sets: Int = 2, restSeconds: Int = 90) = Prescription(
        exerciseId = "squat",
        orderIndex = 0,
        sets = sets,
        repsMin = 5,
        repsMax = 8,
        durationSeconds = null,
        intensity = null,
        restSeconds = restSeconds,
        tempo = null,
        notes = null,
        deloadModifier = null,
        exercise = null,
        targetWeightLbs = 135.0,
    )

    private fun draft(
        sets: Int = 2,
        logged: Map<PrescriptionKey, List<LoggedSet>> = emptyMap(),
    ): WorkoutSessionDraft {
        val day = WorkoutDay(
            dayId = "d1",
            label = "Lower A",
            dayOfWeek = DayOfWeek.MON,
            locationId = "gym-1",
            locationName = "Home Gym",
            orderIndex = 0,
            blocks = listOf(
                Block("b1", BlockType.MAIN, "Squat", 0, listOf(prescription(sets = sets))),
            ),
        )
        val scheduled = ScheduledWorkout(
            scheduledId = "s1",
            date = LocalDate(2026, 9, 23),
            phaseId = "p1",
            dayId = "d1",
            dayLabel = "Lower A",
            weekIndexInPhase = 0,
            isDeload = false,
            locationId = "gym-1",
            locationName = "Home Gym",
            status = ScheduledStatus.PLANNED,
            session = day,
        )
        return WorkoutSessionDraft(
            programId = "prog-1",
            scheduledId = "s1",
            startedAt = FIXED_NOW,
            lastActivityAt = FIXED_NOW,
            status = DraftStatus.ACTIVE,
            scheduled = scheduled,
            logged = logged,
        )
    }

    private fun vmWith(repo: FakeSessionRepository): WorkoutSessionViewModel {
        val vm = WorkoutSessionViewModel(repo, programId = "prog-1", scheduledId = "s1")
        vm.now = { FIXED_NOW }
        return vm
    }

    @Test
    fun loggingASetPersistsToTheDraftAndReEmits() = runTest {
        val repo = FakeSessionRepository(initial = draft())
        val vm = vmWith(repo)
        advanceUntilIdle()

        assertEquals(0, vm.state.value.draft?.totalLoggedSets)

        vm.logSet(key, LoggedSet(weightLbs = 135.0, reps = 8))
        advanceUntilIdle()

        // The write went through the repo (offline-first Room contract)...
        assertEquals(1, repo.updateSetsCalls)
        // ...and the reactive draft re-emitted the new set.
        val loggedNow = vm.state.value.draft?.logged?.get(key)
        assertNotNull(loggedNow)
        assertEquals(1, loggedNow.size)
        assertEquals(8, loggedNow.first().reps)
        // A rest countdown started (not yet complete).
        assertNotNull(vm.restTimer.value)
        assertEquals(90, vm.restTimer.value?.totalSeconds)
        assertNull(vm.state.value.prompt)
    }

    @Test
    fun restTimerIsASingleSourceThatTicksDownOncePerSecond() = runTest {
        val repo = FakeSessionRepository(initial = draft(sets = 3)) // won't auto-complete
        val vm = vmWith(repo)
        advanceUntilIdle()

        // now() is fixed, but the ticker re-derives from the wall-clock end anchor.
        // Move the VM's clock forward AND advance virtual time so the ticker fires.
        var clock = FIXED_NOW
        vm.now = { clock }

        vm.logSet(key, LoggedSet(weightLbs = 135.0, reps = 8))
        advanceUntilIdle()
        assertEquals(90, vm.restTimer.value?.remainingSeconds)
        assertTrue(vm.restTimer.value?.isRunning == true)

        // Advance both real wall-clock and the coroutine scheduler by 3s.
        clock = FIXED_NOW.plusSecs(3)
        advanceTimeBy(3_000)
        advanceUntilIdle()
        // ONE source: remaining tracked wall-clock, no drift, no second state.
        assertEquals(87, vm.restTimer.value?.remainingSeconds)

        // Skip rest → the single source clears immediately for every consumer.
        vm.dismissRest()
        assertNull(vm.restTimer.value)
    }

    @Test
    fun completingEveryPrescribedSetAggregatesAndAutoOpensFinish() = runTest {
        val repo = FakeSessionRepository(initial = draft(sets = 2))
        val vm = vmWith(repo)
        advanceUntilIdle()

        vm.logSet(key, LoggedSet(weightLbs = 135.0, reps = 8))
        advanceUntilIdle()
        // Mid-session: rest is running, no finish prompt.
        assertNotNull(vm.restTimer.value)
        assertNull(vm.state.value.prompt)

        vm.logSet(key, LoggedSet(weightLbs = 135.0, reps = 6))
        advanceUntilIdle()

        // The last set completed the whole session: auto-open the summary, no
        // dangling rest (the single timer source was cleared), chime flag set.
        assertEquals(SessionPrompt.FINISH_SUMMARY, vm.state.value.prompt)
        assertTrue(vm.state.value.autoCompleted)
        assertNull(vm.restTimer.value)
        assertEquals(2, vm.state.value.draft?.totalLoggedSets)

        // Finishing uploads COMPLETED with all logged actuals aggregated.
        vm.confirmFinish(feeling = 4)
        advanceUntilIdle()
        assertEquals(1, repo.finishCalls)
        assertEquals(4, repo.lastFeeling)
        assertTrue(vm.state.value.completed)
    }
}

private val FIXED_NOW: Instant = Instant.fromEpochSeconds(1_700_000_000)
private fun Instant.plusSecs(s: Long) = Instant.fromEpochSeconds(epochSeconds + s, nanosecondsOfSecond.toLong())

/**
 * In-memory fake of the live-session repo: a reactive draft flow (the Room mirror)
 * that set writes mutate and re-emit, plus finish/skip/discard bookkeeping.
 */
private class FakeSessionRepository(
    initial: WorkoutSessionDraft,
) : WorkoutSessionRepository {

    private val drafts = MutableStateFlow<WorkoutSessionDraft?>(initial)
    var updateSetsCalls = 0
    var finishCalls = 0
    var lastFeeling: Int? = null

    override suspend fun start(programId: String, scheduledId: String): Result<Unit> = Result.success(Unit)

    override suspend fun peekDraft(programId: String, scheduledId: String): WorkoutSessionDraft? =
        drafts.value

    override fun observeDraft(programId: String, scheduledId: String): Flow<WorkoutSessionDraft?> = drafts

    override suspend fun updateSets(
        programId: String,
        scheduledId: String,
        key: PrescriptionKey,
        sets: List<LoggedSet>,
    ): Result<Unit> {
        updateSetsCalls++
        drafts.update { current -> current?.copy(logged = current.logged + (key to sets)) }
        return Result.success(Unit)
    }

    override suspend fun lastSets(
        programId: String,
        scheduledId: String,
    ): Map<String, List<LoggedSet>> = emptyMap()

    override suspend fun markStarted(programId: String, scheduledId: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun finish(programId: String, scheduledId: String, feeling: Int?): Result<Unit> {
        finishCalls++
        lastFeeling = feeling
        drafts.value = null
        return Result.success(Unit)
    }

    override suspend fun fetchRecap(programId: String, scheduledId: String): String? = "Nice work."

    override suspend fun skip(programId: String, scheduledId: String): Result<Unit> {
        drafts.value = null
        return Result.success(Unit)
    }

    override suspend fun discard(programId: String, scheduledId: String): Result<Unit> {
        drafts.value = null
        return Result.success(Unit)
    }

    // --- Banner-surface methods (merged WorkoutSessionRepository, Wave D-i) ---
    // Not exercised by these live-session tests; minimal conformance so the fake
    // satisfies the single merged interface.
    override fun observeDrafts(): Flow<List<WorkoutSessionDraft>> =
        drafts.map { listOfNotNull(it) }
    override fun observeParkedCompletions(): Flow<List<ParkedCompletion>> = flowOf(emptyList())
    override suspend fun reset(programId: String, scheduledId: String): Result<Unit> = Result.success(Unit)
    override suspend fun restoreParked(programId: String, scheduledId: String): Result<Unit> = Result.success(Unit)
    override suspend fun discardParked(programId: String, scheduledId: String): Result<Unit> = Result.success(Unit)
}
