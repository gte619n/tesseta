package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave D — port of the Android `WorkoutDetailViewModelTest`,
 * plus the Wave-D prior-performance (last-sets) prefill coverage the task asked
 * for. Verifies the phase+day resolution out of the deep tree, the prior-
 * performance read, "run today", and the shallow-tree "keep loading" guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: FakeWorkoutProgramRepository) = WorkoutDetailViewModel(
        repository = repo,
        programId = "prog-1",
        phaseId = "phase-1",
        dayId = "day-1",
    )

    @Test
    fun resolvesTheRequestedPhaseAndDay() = runTest {
        val repo = FakeWorkoutProgramRepository()
        val vm = vm(repo)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("Strength Block", s.programTitle)
        assertEquals("Base", s.phaseTitle)
        assertEquals("day-1", s.day?.dayId)
        assertNull(s.error)
    }

    @Test
    fun prefillsPriorPerformanceFromLastSets() = runTest {
        val repo = FakeWorkoutProgramRepository()
        repo.lastSets = mapOf(
            "ex-1" to listOf(
                LoggedSet(weightLbs = 135.0, reps = 8),
                LoggedSet(weightLbs = 135.0, reps = 7),
            ),
        )
        val vm = vm(repo)
        advanceUntilIdle()

        val prior = vm.state.value.priorPerformance["ex-1"]
        assertEquals(2, prior?.size)
        assertEquals(135.0, prior?.first()?.weightLbs)
        // The single-sourced formatter renders the "last time" hint.
        assertEquals("135 lb × 8 · 135 lb × 7", loggedSetsSummary(prior!!))
    }

    @Test
    fun priorPerformanceReadFailureLeavesTheWorkoutVisible() = runTest {
        val repo = FakeWorkoutProgramRepository()
        repo.lastSetsFails = true
        val vm = vm(repo)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("day-1", s.day?.dayId)          // workout still renders
        assertTrue(s.priorPerformance.isEmpty())     // hint just absent
        assertNull(s.error)
    }

    @Test
    fun runTodayMaterializesAndSignalsNavigation() = runTest {
        val repo = FakeWorkoutProgramRepository(runDayResult = Result.success("sched-99"))
        val vm = vm(repo)
        advanceUntilIdle()

        vm.startToday()
        advanceUntilIdle()

        assertEquals("sched-99", vm.state.value.startedScheduledId)
        assertFalse(vm.state.value.starting)
        vm.consumeStarted()
        assertNull(vm.state.value.startedScheduledId)
    }

    @Test
    fun shallowProgramWithoutDaysKeepsLoadingRatherThanNotFound() = runTest {
        // A program whose phase has no days yet (still upgrading to the deep tree).
        val shallow = sampleProgram(phases = listOf(samplePhase(days = emptyList())))
        val repo = FakeWorkoutProgramRepository()
        repo.programFlow("prog-1").value = shallow
        val vm = vm(repo)
        advanceUntilIdle()

        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }
}
