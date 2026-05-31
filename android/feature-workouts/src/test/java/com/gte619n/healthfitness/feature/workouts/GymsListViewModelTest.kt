package com.gte619n.healthfitness.feature.workouts

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.workouts.LocationRepository
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
class GymsListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: LocationRepository = mockk()

    @Test
    fun `success populates locations`() = runTest {
        coEvery { repo.list() } returns Result.success(listOf(Fixtures.location()))
        val vm = GymsListViewModel(repo)

        vm.state.test {
            // initial loading state
            assertEquals(true, awaitItem().loading)
            advanceUntilIdle()
            val loaded = awaitItem()
            assertEquals(false, loaded.loading)
            assertEquals(1, loaded.locations.size)
            assertNull(loaded.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failure surfaces error`() = runTest {
        coEvery { repo.list() } returns Result.failure(RuntimeException("boom"))
        val vm = GymsListViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals("boom", state.error)
    }

    @Test
    fun `refresh re-invokes repository`() = runTest {
        coEvery { repo.list() } returns Result.success(emptyList())
        val vm = GymsListViewModel(repo)
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.list() }
    }
}
