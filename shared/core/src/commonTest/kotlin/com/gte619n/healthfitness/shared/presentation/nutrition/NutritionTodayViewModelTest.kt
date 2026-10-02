package com.gte619n.healthfitness.shared.presentation.nutrition

import com.gte619n.healthfitness.shared.data.EntryPatchRequest
import com.gte619n.healthfitness.shared.data.NutritionOp
import com.gte619n.healthfitness.shared.data.NutritionOpType
import com.gte619n.healthfitness.shared.domain.nutrition.Meal
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave C — port of the Android NutritionTodayViewModel tests.
 * Verifies the reactive day stream, offline-first cache seeding, optimistic
 * move/delete, the pending-op synthetic-row projection, and the review-sheet
 * state flags. `viewModelScope` runs on the injected main dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionTodayViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vmWith(
        repo: FakeNutritionDayRepository = FakeNutritionDayRepository(),
        queue: FakeNutritionOpQueue = FakeNutritionOpQueue(),
        date: String = "2026-09-23",
    ) = NutritionTodayViewModel(repo, queue, date)

    @Test
    fun loadSeedsFromCacheThenRevalidatesFromNetwork() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = vmWith(repo)
        vm.refresh()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals("2026-09-23", s.date)
        assertEquals(1, s.day!!.meals.flatMap { it.entries }.size)
    }

    @Test
    fun reactiveStreamEmissionUpdatesTheDay() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = vmWith(repo)
        vm.refresh()
        advanceUntilIdle()

        repo.emit(sampleDay(entries = listOf(sampleEntry(entryId = "e-1"), sampleEntry(entryId = "e-2"))))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.day!!.meals.flatMap { it.entries }.size)
    }

    @Test
    fun errorOnColdLoadWithNoCacheSurfaces() = runTest {
        val repo = FakeNutritionDayRepository(cached = null, dayThrows = true)
        val vm = vmWith(repo)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.error != null)
    }

    @Test
    fun deleteMarksPendingThenClears() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = vmWith(repo)
        vm.refresh(); advanceUntilIdle()

        vm.deleteEntry("e-1")
        advanceUntilIdle()
        assertEquals(listOf("e-1"), repo.deleted)
        assertFalse(vm.state.value.pendingEntryIds.contains("e-1"))
    }

    @Test
    fun deleteIgnoresSyntheticPendingRow() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = vmWith(repo)
        vm.deleteEntry(PENDING_CAPTURE_PREFIX + "op-1")
        advanceUntilIdle()
        assertTrue(repo.deleted.isEmpty())
    }

    @Test
    fun moveEntryOptimisticallyHopsThenPatches() = runTest {
        val repo = FakeNutritionDayRepository(
            days = MutableStateFlow(sampleDay(entries = listOf(sampleEntry(entryId = "e-1", meal = "BREAKFAST")))),
        )
        val vm = vmWith(repo)
        vm.refresh(); advanceUntilIdle()

        vm.moveEntry("e-1", "LUNCH")
        // Optimistic hop happens synchronously before the patch coroutine runs.
        val lunch = vm.state.value.day!!.meals.first { it.meal == "LUNCH" }
        assertEquals("e-1", lunch.entries.single().entryId)
        advanceUntilIdle()
        assertEquals("e-1" to EntryPatchRequest(meal = "LUNCH"), repo.patched.single())
    }

    @Test
    fun pendingOpsProjectSyntheticRow() = runTest {
        val queue = FakeNutritionOpQueue()
        val repo = FakeNutritionDayRepository()
        val vm = vmWith(repo, queue)
        vm.refresh(); advanceUntilIdle()

        queue.ops.value = listOf(
            NutritionOp(id = "op-1", type = NutritionOpType.CAPTURE_PHOTO, date = "2026-09-23", mealWire = "LUNCH", label = "New photo"),
        )
        advanceUntilIdle()

        val display = vm.state.value.displayDay!!
        val synthetic = display.meals.flatMap { it.entries }.first { it.entryId.startsWith(PENDING_CAPTURE_PREFIX) }
        assertEquals("ANALYZING", synthetic.analysisStatus)
        assertEquals("New photo", synthetic.foodName)
        // The synthetic row contributes nothing to totals (macros EMPTY).
        assertEquals(0.0, synthetic.macros.caloriesKcal ?: 0.0)
    }

    @Test
    fun completedOpTriggersReload() = runTest {
        val queue = FakeNutritionOpQueue(ops = MutableStateFlow(listOf(
            NutritionOp(id = "op-1", type = NutritionOpType.CAPTURE_PHOTO, date = "2026-09-23", mealWire = "LUNCH", label = "New photo"),
        )))
        val repo = FakeNutritionDayRepository()
        val vm = vmWith(repo, queue)
        vm.refresh(); advanceUntilIdle()

        queue.ops.value = emptyList() // op completed → shrink → reload
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun addQuickEntrySubmitsRequest() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = vmWith(repo)
        vm.addQuickEntry(Meal.SNACK, "Apple", com.gte619n.healthfitness.shared.domain.nutrition.Macros(caloriesKcal = 95.0))
        advanceUntilIdle()
        assertEquals("Apple", repo.added.single().foodName)
        assertEquals("SNACK", repo.added.single().meal)
        assertEquals("MANUAL", repo.added.single().source)
    }

    @Test
    fun adjustReviewOpenAndCloseTogglesState() = runTest {
        val vm = vmWith()
        vm.reviewAdjust("e-1")
        assertEquals("e-1", vm.state.value.reviewingAdjustId)
        vm.closeAdjustReview()
        assertNull(vm.state.value.reviewingAdjustId)
    }

    @Test
    fun dayNavigationMovesForwardAndBackAcrossMonthBoundary() = runTest {
        val vm = vmWith(date = "2026-01-31")
        vm.nextDay(); advanceUntilIdle()
        assertEquals("2026-02-01", vm.state.value.date)
        vm.previousDay(); advanceUntilIdle()
        assertEquals("2026-01-31", vm.state.value.date)
    }
}
