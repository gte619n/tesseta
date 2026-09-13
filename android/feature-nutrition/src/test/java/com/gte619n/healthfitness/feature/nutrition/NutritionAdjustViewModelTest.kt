package com.gte619n.healthfitness.feature.nutrition

import com.gte619n.healthfitness.data.db.entity.NutritionOpEntity
import com.gte619n.healthfitness.data.db.entity.NutritionOpType
import com.gte619n.healthfitness.data.nutrition.NutritionOpStore
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.data.sync.SyncSignals
import com.gte619n.healthfitness.domain.nutrition.AdjustPreviewResponse
import com.gte619n.healthfitness.domain.nutrition.AdjustStatus
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.MealAdjustment
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class NutritionAdjustViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val date = LocalDate.now()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun reviewEntry() = Entry(
        entryId = "e1",
        meal = "DINNER",
        foodName = "Lentils and rice",
        quantity = 1.0,
        macros = Macros(caloriesKcal = 500.0),
        source = "PHOTO",
        adjustment = MealAdjustment(
            status = AdjustStatus.PENDING_REVIEW,
            instruction = "swap lentils for couscous",
            proposal = AdjustPreviewResponse(
                mealName = "Pearl couscous and rice",
                newTotals = Macros(caloriesKcal = 560.0),
                oldTotals = Macros(caloriesKcal = 500.0),
            ),
        ),
    )

    private fun dayWith(entry: Entry) = NutritionDay(
        date = date.toString(),
        totals = entry.macros,
        meals = listOf(MealGroup(entry.meal, entry.macros, listOf(entry))),
    )

    private fun viewModel(repo: NutritionRepository): NutritionTodayViewModel {
        val ops = mockk<NutritionOpStore> { coEvery { observeAll() } returns flowOf(emptyList()) }
        return NutritionTodayViewModel(repo, ops, SyncSignals())
    }

    @Test
    fun `submitAdjust enqueues the durable op and closes the edit sheets`() = runTest {
        val repo = mockk<NutritionRepository>(relaxed = true)
        val vm = viewModel(repo)

        vm.submitAdjust("e1", "swap lentils for couscous", saveAsMeal = true)

        coVerify { repo.submitAdjust(date.toString(), "e1", "swap lentils for couscous", true) }
        assertNull(vm.state.value.editingEntry)
        assertNull(vm.state.value.editingComposite)
    }

    @Test
    fun `reviewAdjust opens the review sheet and closes edit sheets`() = runTest {
        val repo = mockk<NutritionRepository>(relaxed = true)
        val vm = viewModel(repo)

        vm.reviewAdjust(reviewEntry())

        assertEquals("e1", vm.state.value.reviewingAdjust?.entryId)
        assertNull(vm.state.value.editingComposite)
        assertNull(vm.state.value.editingEntry)
    }

    @Test
    fun `commitAdjust commits then refreshes and closes the review`() = runTest {
        val committed = reviewEntry().copy(
            foodName = "Pearl couscous and rice",
            macros = Macros(caloriesKcal = 560.0),
            adjustment = null,
        )
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { commitAdjust(any(), "e1", any()) } returns committed
            coEvery { day(any()) } returns dayWith(committed)
        }
        val vm = viewModel(repo)
        vm.reviewAdjust(reviewEntry())

        vm.commitAdjust("e1", saveAsMeal = false)

        coVerify { repo.commitAdjust(date.toString(), "e1", false) }
        val state = vm.state.value
        assertNull(state.reviewingAdjust)
        assertFalse(state.savingAdjust)
    }

    @Test
    fun `commitAdjust forwards the review-time saveAsMeal override`() = runTest {
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { day(any()) } returns dayWith(reviewEntry().copy(adjustment = null))
        }
        val vm = viewModel(repo)
        vm.reviewAdjust(reviewEntry())

        vm.commitAdjust("e1", saveAsMeal = true)

        coVerify { repo.commitAdjust(date.toString(), "e1", true) }
    }

    @Test
    fun `discardAdjust clears the proposal and closes the review`() = runTest {
        val discarded = reviewEntry().copy(adjustment = null)
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { discardAdjust(any(), "e1") } returns discarded
            coEvery { day(any()) } returns dayWith(discarded)
        }
        val vm = viewModel(repo)
        vm.reviewAdjust(reviewEntry())

        vm.discardAdjust("e1")

        coVerify { repo.discardAdjust(date.toString(), "e1") }
        assertNull(vm.state.value.reviewingAdjust)
    }

    // --- withPendingOps adjust decoration ---

    private fun adjustOp() = NutritionOpEntity(
        id = "op1",
        type = NutritionOpType.ADJUST_MEAL.name,
        date = date.toString(),
        mealWire = "",
        clientEntryId = "cid",
        idempotencyKey = "op1",
        payloadJson = """{"targetEntryId":"e1","instruction":"fix","saveAsMeal":false}""",
        jpegPath = null,
        label = "Adjusting…",
        attempts = 0,
        nextAttemptAt = 0,
        createdAt = 0,
    )

    @Test
    fun `withPendingOps decorates the target entry as ADJUSTING without adding a row`() {
        val base = dayWith(reviewEntry().copy(adjustment = null))
        val decorated = base.withPendingOps(listOf(adjustOp()), date)!!
        val entries = decorated.meals.single().entries
        assertEquals(1, entries.size) // no synthetic row appended
        assertTrue(entries.single().isAdjusting)
    }
}
