package com.gte619n.healthfitness.feature.workouts.program

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramsListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutProgramRepository = mockk()

    @Test
    fun `initial loading then success populates programs`() = runTest {
        coEvery { repo.list() } returns Result.success(ProgramFixtures.listPrograms)
        val vm = ProgramsListViewModel(repo)

        vm.state.test {
            assertEquals(true, awaitItem().loading)
            advanceUntilIdle()
            val loaded = awaitItem()
            assertEquals(false, loaded.loading)
            assertEquals(2, loaded.programs.size)
            assertNull(loaded.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failure surfaces error`() = runTest {
        coEvery { repo.list() } returns Result.failure(RuntimeException("boom"))
        val vm = ProgramsListViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals("boom", state.error)
    }

    @Test
    fun `refresh re-invokes repository`() = runTest {
        coEvery { repo.list() } returns Result.success(emptyList())
        val vm = ProgramsListViewModel(repo)
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.list() }
    }
}
