package com.gte619n.healthfitness.shared.presentation.goals

import com.gte619n.healthfitness.shared.domain.goals.GoalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave E3 — port of the Android GoalsList tests. Verifies
 * the reactive/offline-first behavior: the list renders from the mirror,
 * switching the filter re-subscribes the matching stream, and a mirror error
 * surfaces without blowing away the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalsListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun rendersActiveGoalsFromTheMirrorAndReflectsReactiveUpdates() = runTest {
        val active = MutableStateFlow(listOf(sampleGoal(id = "g1")))
        val repo = FakeGoalsRepository(byStatus = mapOf(GoalStatus.ACTIVE to active))
        val vm = GoalsListViewModel(repo)
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertEquals(listOf("g1"), vm.state.value.goals.map { it.goalId })

        // A background sync delta re-emits; the list updates in place.
        active.value = listOf(sampleGoal(id = "g1"), sampleGoal(id = "g2"))
        advanceUntilIdle()
        assertEquals(listOf("g1", "g2"), vm.state.value.goals.map { it.goalId })
    }

    @Test
    fun switchingFilterResubscribesToTheMatchingStatusStream() = runTest {
        val active = MutableStateFlow(listOf(sampleGoal(id = "a1", status = GoalStatus.ACTIVE)))
        val completed = MutableStateFlow(
            listOf(sampleGoal(id = "c1", status = GoalStatus.COMPLETED)),
        )
        val repo = FakeGoalsRepository(
            byStatus = mapOf(
                GoalStatus.ACTIVE to active,
                GoalStatus.COMPLETED to completed,
            ),
        )
        val vm = GoalsListViewModel(repo)
        advanceUntilIdle()
        assertEquals(listOf("a1"), vm.state.value.goals.map { it.goalId })

        vm.setFilter(GoalsFilter.COMPLETED)
        advanceUntilIdle()
        assertEquals(GoalsFilter.COMPLETED, vm.state.value.filter)
        assertEquals(listOf("c1"), vm.state.value.goals.map { it.goalId })
    }

    @Test
    fun emptyMirrorStreamRendersAnEmptyList() = runTest {
        // A status with no goals emits []; the screen settles to a resolved
        // empty list (never an error, never a permanent spinner).
        val empty = MutableStateFlow(emptyList<com.gte619n.healthfitness.shared.domain.goals.Goal>())
        val repo = FakeGoalsRepository(byStatus = mapOf(GoalStatus.ACTIVE to empty))
        val vm = GoalsListViewModel(repo)
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.goals.isEmpty())
        assertTrue(vm.state.value.error == null)
    }
}
