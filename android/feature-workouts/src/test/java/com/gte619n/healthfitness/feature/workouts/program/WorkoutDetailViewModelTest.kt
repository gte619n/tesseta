package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutProgramRepository = mockk()
    private val handle = SavedStateHandle(
        mapOf(
            WorkoutsRoutes.ARG_PROGRAM_ID to "p1",
            WorkoutsRoutes.ARG_PHASE_ID to "ph1",
            WorkoutsRoutes.ARG_DAY_ID to "d1",
        ),
    )

    private fun vm(): WorkoutDetailViewModel {
        every { repo.observeProgram("p1") } returns flowOf(ProgramFixtures.deepProgram)
        return WorkoutDetailViewModel(repo, handle)
    }

    @Test
    fun `startToday materializes session and emits scheduledId to open the logger`() = runTest {
        coEvery { repo.runDayToday("p1", "ph1", "d1") } returns Result.success("2026-07-28_d1")

        val vm = vm()
        advanceUntilIdle()
        vm.startToday()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.starting)
        assertEquals("2026-07-28_d1", state.startedScheduledId)
        assertNull(state.error)
        coVerify { repo.runDayToday("p1", "ph1", "d1") }

        // The one-shot signal clears so returning to the screen doesn't re-navigate.
        vm.consumeStarted()
        assertNull(vm.state.value.startedScheduledId)
    }

    @Test
    fun `startToday failure surfaces the message and does not navigate`() = runTest {
        coEvery { repo.runDayToday("p1", "ph1", "d1") } returns
            Result.failure(IllegalStateException("Connect to the internet once to start this workout."))

        val vm = vm()
        advanceUntilIdle()
        vm.startToday()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.starting)
        assertNull(state.startedScheduledId)
        assertEquals("Connect to the internet once to start this workout.", state.error)
    }
}
