package com.gte619n.healthfitness.feature.workouts.program

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsHubViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutSessionRepository = mockk()
    private val drafts = MutableStateFlow<List<WorkoutSessionDraft>>(emptyList())
    private val parked = MutableStateFlow<List<ParkedCompletion>>(emptyList())

    private fun vm(): WorkoutsHubViewModel {
        every { repo.observeDrafts() } returns drafts
        every { repo.observeParkedCompletions() } returns parked
        return WorkoutsHubViewModel(repo)
    }

    @Test
    fun `surfaces the newest draft for the resume banner`() = runTest {
        drafts.value = listOf(ProgramFixtures.activeDraft)
        val vm = vm()

        vm.activeDraft.test {
            // stateIn's initial value, then the draft once collection starts.
            assertNull(awaitItem())
            assertEquals(ProgramFixtures.activeDraft, awaitItem())

            // The banner clears when the draft is finished/skipped/discarded.
            drafts.value = emptyList()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `surfaces the newest parked completion for the recovery banner`() = runTest {
        parked.value = listOf(ProgramFixtures.parkedCompletion)
        val vm = vm()

        vm.parkedCompletion.test {
            assertNull(awaitItem())
            assertEquals(ProgramFixtures.parkedCompletion, awaitItem())

            // The banner clears once the row is restored or discarded.
            parked.value = emptyList()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restore success emits the restored session for navigation`() = runTest {
        coEvery { repo.restoreParked("p1", "s2") } returns
            Result.success(ProgramFixtures.activeDraft)
        val vm = vm()

        vm.restoreParked(ProgramFixtures.parkedCompletion)
        advanceUntilIdle()

        assertEquals(ProgramFixtures.parkedCompletion, vm.restoredSession.value)
        assertNull(vm.parkedError.value)

        // The route consumes the event after navigating into the logger.
        vm.consumeRestoredSession()
        assertNull(vm.restoredSession.value)
    }

    @Test
    fun `restore failure surfaces an error and does not navigate`() = runTest {
        coEvery { repo.restoreParked("p1", "s2") } returns
            Result.failure(RuntimeException("session gone"))
        val vm = vm()

        vm.restoreParked(ProgramFixtures.parkedCompletion)
        advanceUntilIdle()

        assertNull(vm.restoredSession.value)
        assertEquals("session gone", vm.parkedError.value)
    }

    @Test
    fun `discard delegates to the repository`() = runTest {
        coEvery { repo.discardParked("p1", "s2") } returns Result.success(Unit)
        val vm = vm()

        vm.discardParked(ProgramFixtures.parkedCompletion)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.discardParked("p1", "s2") }
        assertNull(vm.parkedError.value)
    }
}
