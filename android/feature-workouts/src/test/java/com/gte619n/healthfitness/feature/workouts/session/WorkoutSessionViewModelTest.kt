package com.gte619n.healthfitness.feature.workouts.session

import androidx.lifecycle.SavedStateHandle
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import com.gte619n.healthfitness.feature.workouts.program.ProgramFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSessionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutSessionRepository = mockk()
    private val timers = WorkoutSessionTimers()
    private val handle = SavedStateHandle(
        mapOf(
            WorkoutsRoutes.ARG_PROGRAM_ID to "p1",
            WorkoutsRoutes.ARG_SCHEDULED_ID to "s2",
        ),
    )
    private val draftFlow = MutableStateFlow<WorkoutSessionDraft?>(ProgramFixtures.activeDraft)

    /** The fixture's only logged prescription: the squat (sets=3, rest 90s). */
    private val squatKey = PrescriptionKey("b-main", 0)

    /** The fixture's timed prescription: the plank (duration 45s, rest 30s). */
    private val plankKey = PrescriptionKey("b-core", 0)

    private fun vm(): WorkoutSessionViewModel {
        coEvery { repo.start("p1", "s2") } returns Result.success(ProgramFixtures.activeDraft)
        every { repo.observeDraft("p1", "s2") } returns draftFlow
        coEvery { repo.lastSets("p1", "s2") } returns emptyMap()
        return WorkoutSessionViewModel(repo, timers, handle)
    }

    @Test
    fun `start resumes the draft into state`() = runTest {
        val vm = vm()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals(ProgramFixtures.activeDraft, state.draft)
        assertNull(state.error)
        coVerify(exactly = 1) { repo.start("p1", "s2") }
    }

    @Test
    fun `start failure surfaces error`() = runTest {
        coEvery { repo.start("p1", "s2") } returns Result.failure(RuntimeException("not mirrored"))
        coEvery { repo.lastSets("p1", "s2") } returns emptyMap()
        val vm = WorkoutSessionViewModel(repo, timers, handle)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals("not mirrored", state.error)
        assertNull(state.draft)
    }

    @Test
    fun `checking the next row appends a defaulted set and starts the rest timer`() = runTest {
        val sets = slot<List<LoggedSet>>()
        coEvery {
            repo.updateSets("p1", "s2", squatKey, capture(sets))
        } returns Result.success(ProgramFixtures.activeDraft)

        val vm = vm()
        advanceUntilIdle()
        // Two minutes after the fixture's first set (logged at 14:05).
        vm.now = { Instant.parse("2026-06-03T14:07:00Z") }

        vm.toggleSet(squatKey, 1)
        advanceUntilIdle()

        assertEquals(2, sets.captured.size)
        val appended = sets.captured.last()
        // Weight and reps carry over from the previous set; RPE stays manual.
        assertEquals(135.0, appended.weightLbs)
        assertEquals(8, appended.reps)
        assertNull(appended.rpe)
        // Actual rest = seconds since the previously logged set.
        assertEquals(120, appended.restSeconds)
        assertEquals(Instant.parse("2026-06-03T14:07:00Z"), appended.completedAt)
        // The prescribed 90s rest countdown starts on the shared timer bus.
        val rest = timers.rest.value
        assertNotNull(rest)
        assertEquals(90, rest!!.totalSeconds)
    }

    @Test
    fun `first set prefills from the last time the exercise was done`() = runTest {
        val sets = slot<List<LoggedSet>>()
        coEvery {
            repo.updateSets("p1", "s2", squatKey, capture(sets))
        } returns Result.success(ProgramFixtures.activeDraft)
        // The previous session of the squat: 200 x 5.
        coEvery { repo.lastSets("p1", "s2") } returns
            mapOf("ex-squat" to listOf(LoggedSet(weightLbs = 200.0, reps = 5)))
        // A fresh draft with nothing logged yet, so set 1 has no in-session carry.
        draftFlow.value = ProgramFixtures.activeDraft.copy(logged = emptyMap())

        coEvery { repo.start("p1", "s2") } returns Result.success(ProgramFixtures.activeDraft)
        every { repo.observeDraft("p1", "s2") } returns draftFlow
        val vm = WorkoutSessionViewModel(repo, timers, handle)
        advanceUntilIdle()

        vm.toggleSet(squatKey, 0)
        advanceUntilIdle()

        val appended = sets.captured.single()
        assertEquals(200.0, appended.weightLbs)
        assertEquals(5, appended.reps)
    }

    @Test
    fun `checking a timed exercise defaults its hold from the prescription`() = runTest {
        val sets = slot<List<LoggedSet>>()
        coEvery {
            repo.updateSets("p1", "s2", plankKey, capture(sets))
        } returns Result.success(ProgramFixtures.activeDraft)

        val vm = vm()
        advanceUntilIdle()

        vm.toggleSet(plankKey, 0)
        advanceUntilIdle()

        val appended = sets.captured.single()
        // Timed exercises carry the prescribed hold, not weight/reps.
        assertEquals(45, appended.durationSeconds)
        assertNull(appended.weightLbs)
        assertNull(appended.reps)
        // The plank's 30s rest countdown starts.
        assertEquals(30, timers.rest.value!!.totalSeconds)
    }

    @Test
    fun `logTimedSet records the measured hold and starts the rest timer`() = runTest {
        val sets = slot<List<LoggedSet>>()
        coEvery {
            repo.updateSets("p1", "s2", plankKey, capture(sets))
        } returns Result.success(ProgramFixtures.activeDraft)

        val vm = vm()
        advanceUntilIdle()
        vm.now = { Instant.parse("2026-06-03T14:07:00Z") }

        vm.logTimedSet(plankKey, 38)
        advanceUntilIdle()

        val appended = sets.captured.single()
        // The measured hold wins over the prescribed default.
        assertEquals(38, appended.durationSeconds)
        assertNull(appended.weightLbs)
        assertEquals(120, appended.restSeconds)
        assertEquals(30, timers.rest.value!!.totalSeconds)
    }

    @Test
    fun `unchecking a logged row removes it without starting a rest timer`() = runTest {
        val sets = slot<List<LoggedSet>>()
        coEvery {
            repo.updateSets("p1", "s2", squatKey, capture(sets))
        } returns Result.success(ProgramFixtures.activeDraft)

        val vm = vm()
        advanceUntilIdle()

        vm.toggleSet(squatKey, 0)
        advanceUntilIdle()

        assertTrue(sets.captured.isEmpty())
        assertNull(timers.rest.value)
    }

    @Test
    fun `editing a logged set replaces it in place`() = runTest {
        val sets = slot<List<LoggedSet>>()
        coEvery {
            repo.updateSets("p1", "s2", squatKey, capture(sets))
        } returns Result.success(ProgramFixtures.activeDraft)

        val vm = vm()
        advanceUntilIdle()

        val edited = ProgramFixtures.activeDraft.logged[squatKey]!!.first().copy(weightLbs = 145.0)
        vm.editSet(squatKey, 0, edited)
        advanceUntilIdle()

        assertEquals(listOf(edited), sets.captured)
    }

    @Test
    fun `finish flow shows the summary then enqueues completion and surfaces the recap`() = runTest {
        coEvery { repo.finish("p1", "s2") } returns Result.success(Unit)
        coEvery { repo.fetchRecap("p1", "s2") } returns "Strong squats — nice work."
        val vm = vm()
        advanceUntilIdle()
        timers.startRest(90)

        vm.requestFinish()
        assertEquals(SessionPrompt.FINISH_SUMMARY, vm.state.value.prompt)
        // The summary is a checkpoint — nothing uploads until confirmation.
        coVerify(exactly = 0) { repo.finish(any(), any()) }

        vm.confirmFinish()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.finish("p1", "s2") }
        // Finish lands on the recap summary (not closed yet); rest cleared.
        val finished = vm.state.value
        assertTrue(finished.completed)
        assertFalse(finished.closed)
        assertFalse(finished.recapLoading)
        assertEquals("Strong squats — nice work.", finished.recap)
        assertNull(finished.prompt)
        assertNull(timers.rest.value)

        // Dismissing the recap summary pops the logger.
        vm.dismissCompleted()
        assertTrue(vm.state.value.closed)
    }

    @Test
    fun `finish with no recap still completes and closes on dismiss`() = runTest {
        coEvery { repo.finish("p1", "s2") } returns Result.success(Unit)
        coEvery { repo.fetchRecap("p1", "s2") } returns null
        val vm = vm()
        advanceUntilIdle()

        vm.confirmFinish()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.completed)
        assertNull(state.recap)
        assertFalse(state.recapLoading)
        vm.dismissCompleted()
        assertTrue(vm.state.value.closed)
    }

    @Test
    fun `skip confirms then uploads SKIPPED and closes`() = runTest {
        coEvery { repo.skip("p1", "s2") } returns Result.success(Unit)
        val vm = vm()
        advanceUntilIdle()

        vm.requestSkip()
        assertEquals(SessionPrompt.SKIP, vm.state.value.prompt)
        vm.confirmSkip()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.skip("p1", "s2") }
        assertTrue(vm.state.value.closed)
    }

    @Test
    fun `discard deletes the local draft and closes without uploading`() = runTest {
        coEvery { repo.discard("p1", "s2") } returns Result.success(Unit)
        val vm = vm()
        advanceUntilIdle()

        vm.requestDiscard()
        assertEquals(SessionPrompt.DISCARD, vm.state.value.prompt)
        vm.confirmDiscard()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.discard("p1", "s2") }
        coVerify(exactly = 0) { repo.finish(any(), any()) }
        coVerify(exactly = 0) { repo.skip(any(), any()) }
        assertTrue(vm.state.value.closed)
    }

    @Test
    fun `terminal action failure surfaces error without closing`() = runTest {
        coEvery { repo.finish("p1", "s2") } returns Result.failure(RuntimeException("boom"))
        val vm = vm()
        advanceUntilIdle()

        vm.requestFinish()
        vm.confirmFinish()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.closed)
        assertEquals("boom", state.error)
        assertNull(state.prompt)
    }

    @Test
    fun `dismissing a prompt leaves the session running`() = runTest {
        val vm = vm()
        advanceUntilIdle()

        vm.requestSkip()
        vm.dismissPrompt()

        assertNull(vm.state.value.prompt)
        assertFalse(vm.state.value.closed)
    }
}
