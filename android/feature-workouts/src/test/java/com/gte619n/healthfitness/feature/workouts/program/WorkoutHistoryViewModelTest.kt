package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutHistoryPage
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutHistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutProgramRepository = mockk()

    private fun row(id: String, phaseId: String = "ph1") = ScheduledWorkout(
        scheduledId = id,
        date = LocalDate.parse("2026-06-20"),
        phaseId = phaseId,
        dayId = "d",
        dayLabel = id,
        weekIndexInPhase = 1,
        isDeload = false,
        locationId = "",
        locationName = null,
        status = ScheduledStatus.COMPLETED,
    )

    @Test
    fun `initial load fetches the first page`() = runTest {
        coEvery { repo.workoutHistoryPage(0, any()) } returns Result.success(
            WorkoutHistoryPage(items = listOf(row("a"), row("b")), page = 0, total = 3, hasMore = true),
        )

        val vm = WorkoutHistoryViewModel(repo)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("a", "b"), s.sessions.map { it.scheduledId })
        assertTrue(s.hasMore)
    }

    @Test
    fun `loadMore appends the next page then stops when hasMore is false`() = runTest {
        coEvery { repo.workoutHistoryPage(0, any()) } returns Result.success(
            WorkoutHistoryPage(items = listOf(row("a")), page = 0, total = 2, hasMore = true),
        )
        coEvery { repo.workoutHistoryPage(1, any()) } returns Result.success(
            WorkoutHistoryPage(items = listOf(row("b")), page = 1, total = 2, hasMore = false),
        )

        val vm = WorkoutHistoryViewModel(repo)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.state.value.sessions.map { it.scheduledId })
        assertFalse(vm.state.value.hasMore)

        // hasMore is false now → another loadMore is a no-op (no page 2 request).
        vm.loadMore()
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.workoutHistoryPage(1, any()) }
        coVerify(exactly = 0) { repo.workoutHistoryPage(2, any()) }
    }

    @Test
    fun `failed first load surfaces an error and retry reloads`() = runTest {
        coEvery { repo.workoutHistoryPage(0, any()) } returnsMany listOf(
            Result.failure(RuntimeException("network down")),
            Result.success(
                WorkoutHistoryPage(items = listOf(row("a")), page = 0, total = 1, hasMore = false),
            ),
        )

        val vm = WorkoutHistoryViewModel(repo)
        advanceUntilIdle()
        assertEquals("network down", vm.state.value.error)

        vm.load()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.error)
        assertEquals(1, vm.state.value.sessions.size)
    }
}
