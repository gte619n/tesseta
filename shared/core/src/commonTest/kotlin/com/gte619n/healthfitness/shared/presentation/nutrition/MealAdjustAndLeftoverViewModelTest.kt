package com.gte619n.healthfitness.shared.presentation.nutrition

import com.gte619n.healthfitness.shared.data.AdjustProposal
import com.gte619n.healthfitness.shared.data.AdjustStatus
import com.gte619n.healthfitness.shared.data.Leftover
import com.gte619n.healthfitness.shared.data.LeftoverProposal
import com.gte619n.healthfitness.shared.data.LeftoverStatus
import com.gte619n.healthfitness.shared.data.MealAdjustment
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MealAdjustAndLeftoverViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun readyAdjustment(save: Boolean = false) = MealAdjustment(
        status = AdjustStatus.PENDING_REVIEW,
        saveAsMeal = save,
        proposal = AdjustProposal(
            mealName = "Couscous bowl",
            packagedProduct = false,
            newTotals = Macros(caloriesKcal = 500.0),
            oldTotals = Macros(caloriesKcal = 420.0),
        ),
    )

    @Test
    fun adjustReviewShowsPendingReviewAndApplyCommits() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = MealAdjustViewModel(repo, "2026-09-23", "e-1", "Couscous bowl", readyAdjustment())
        assertEquals(PendingNutritionOp.PENDING_REVIEW, vm.state.value.op)

        vm.apply()
        advanceUntilIdle()
        assertEquals("e-1" to false, repo.adjustCommits.single())
        assertTrue(vm.state.value.done)
    }

    @Test
    fun saveAsMealOverrideIsSentOnCommit() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = MealAdjustViewModel(repo, "2026-09-23", "e-1", "Bowl", readyAdjustment(save = false))
        vm.setSaveAsMeal(true) // user flips it after seeing the diff
        vm.apply()
        advanceUntilIdle()
        assertEquals("e-1" to true, repo.adjustCommits.single())
    }

    @Test
    fun adjustDiscardCallsRepo() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = MealAdjustViewModel(repo, "2026-09-23", "e-1", "Bowl", readyAdjustment())
        vm.discard()
        advanceUntilIdle()
        assertEquals(listOf("e-1"), repo.adjustDiscards)
    }

    @Test
    fun submitBlankInstructionIsNoOp() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = MealAdjustViewModel(repo, "2026-09-23", "e-1", "Bowl")
        vm.submit("   ", saveAsMeal = false)
        advanceUntilIdle()
        assertTrue(repo.adjustSubmits.isEmpty())
    }

    @Test
    fun submitCorrectionEnqueuesAndFinishes() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = MealAdjustViewModel(repo, "2026-09-23", "e-1", "Bowl")
        vm.submit("that's pearl couscous, not lentils", saveAsMeal = true)
        advanceUntilIdle()
        assertEquals(Triple("e-1", "that's pearl couscous, not lentils", true), repo.adjustSubmits.single())
        assertTrue(vm.state.value.done)
    }

    @Test
    fun singleProductSaveToSourceGateRequiresFoodId() {
        val single = MealAdjustment(
            status = AdjustStatus.PENDING_REVIEW,
            proposal = AdjustProposal(
                mealName = "Bar",
                packagedProduct = true,
                items = listOf(com.gte619n.healthfitness.shared.data.AdjustItem(name = "Bar")),
            ),
        )
        val vm = MealAdjustViewModel(FakeNutritionDayRepository(), "2026-09-23", "e-1", "Bar", single)
        assertTrue(vm.state.value.isSingleProduct)
        assertFalse(vm.state.value.canSaveToSource(entryHasFoodId = false))
        assertTrue(vm.state.value.canSaveToSource(entryHasFoodId = true))
    }

    // ---- Leftover ----

    private fun readyLeftover() = Leftover(
        status = LeftoverStatus.PENDING_REVIEW,
        servedMacros = Macros(caloriesKcal = 600.0),
        proposal = LeftoverProposal(
            servedTotals = Macros(caloriesKcal = 600.0),
            consumedTotals = Macros(caloriesKcal = 400.0),
        ),
    )

    @Test
    fun leftoverReviewAppliesAndDiscards() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = LeftoverViewModel(repo, "2026-09-23", "e-1", "Dinner plate", readyLeftover())
        assertEquals(PendingNutritionOp.PENDING_REVIEW, vm.state.value.op)

        vm.apply()
        advanceUntilIdle()
        assertEquals(listOf("e-1"), repo.leftoverApplies)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun appliedLeftoverCanRestore() = runTest {
        val repo = FakeNutritionDayRepository()
        val applied = Leftover(status = LeftoverStatus.APPLIED)
        val vm = LeftoverViewModel(repo, "2026-09-23", "e-1", "Dinner plate", applied)
        assertTrue(vm.state.value.isApplied)
        vm.restoreFullPortion()
        advanceUntilIdle()
        assertEquals(listOf("e-1"), repo.leftoverRestores)
    }

    @Test
    fun applyWithNoProposalIsNoOp() = runTest {
        val repo = FakeNutritionDayRepository()
        val vm = LeftoverViewModel(repo, "2026-09-23", "e-1", "Plate", Leftover(status = LeftoverStatus.ANALYZING))
        vm.apply()
        advanceUntilIdle()
        assertTrue(repo.leftoverApplies.isEmpty())
    }
}
