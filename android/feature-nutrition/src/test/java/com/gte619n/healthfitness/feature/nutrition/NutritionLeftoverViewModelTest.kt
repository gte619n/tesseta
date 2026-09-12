package com.gte619n.healthfitness.feature.nutrition

import com.gte619n.healthfitness.data.db.entity.NutritionOpEntity
import com.gte619n.healthfitness.data.db.entity.NutritionOpType
import com.gte619n.healthfitness.data.nutrition.NutritionOpStore
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.data.sync.SyncSignals
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryIngredient
import com.gte619n.healthfitness.domain.nutrition.Leftover
import com.gte619n.healthfitness.domain.nutrition.LeftoverProposal
import com.gte619n.healthfitness.domain.nutrition.LeftoverStatus
import com.gte619n.healthfitness.domain.nutrition.Macros
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
class NutritionLeftoverViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val date = LocalDate.now()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ingredient(name: String, grams: Double) = EntryIngredient(
        name = name, servingGrams = grams, quantity = 1.0, macros = Macros(caloriesKcal = 100.0),
    )

    private fun reviewEntry() = Entry(
        entryId = "e1",
        meal = "DINNER",
        foodName = "Salmon plate",
        quantity = 1.0,
        macros = Macros(caloriesKcal = 720.0),
        source = "PHOTO",
        ingredients = listOf(ingredient("rice", 150.0), ingredient("salmon", 140.0)),
        leftover = Leftover(
            status = LeftoverStatus.PENDING_REVIEW,
            servedMacros = Macros(caloriesKcal = 720.0),
            proposal = LeftoverProposal(consumedTotals = Macros(caloriesKcal = 540.0)),
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
    fun `reviewLeftovers opens the review sheet and closes edit sheets`() = runTest {
        val repo = mockk<NutritionRepository>(relaxed = true)
        val vm = viewModel(repo)
        val entry = reviewEntry()

        vm.reviewLeftovers(entry)

        assertEquals("e1", vm.state.value.reviewingLeftover?.entryId)
        assertNull(vm.state.value.editingComposite)
        assertNull(vm.state.value.editingEntry)
    }

    @Test
    fun `applyLeftovers commits then refreshes and closes the review`() = runTest {
        val applied = reviewEntry().copy(
            macros = Macros(caloriesKcal = 540.0),
            leftover = Leftover(status = LeftoverStatus.APPLIED, servedMacros = Macros(720.0)),
        )
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { applyLeftovers(any(), "e1") } returns applied
            coEvery { day(any()) } returns dayWith(applied)
        }
        val vm = viewModel(repo)
        vm.reviewLeftovers(reviewEntry())

        vm.applyLeftovers("e1")

        coVerify { repo.applyLeftovers(date.toString(), "e1") }
        val state = vm.state.value
        assertNull(state.reviewingLeftover)
        assertFalse(state.savingLeftover)
        val row = state.day!!.meals.single().entries.single()
        assertEquals(540.0, row.macros.caloriesKcal!!, 0.001)
        assertTrue(row.hasAppliedLeftover)
    }

    @Test
    fun `discardLeftovers clears the proposal and closes the review`() = runTest {
        val discarded = reviewEntry().copy(leftover = null)
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { discardLeftovers(any(), "e1") } returns discarded
            coEvery { day(any()) } returns dayWith(discarded)
        }
        val vm = viewModel(repo)
        vm.reviewLeftovers(reviewEntry())

        vm.discardLeftovers("e1")

        coVerify { repo.discardLeftovers(date.toString(), "e1") }
        assertNull(vm.state.value.reviewingLeftover)
    }

    @Test
    fun `restoreFullPortion resets to served and clears sheets`() = runTest {
        val restored = reviewEntry().copy(macros = Macros(caloriesKcal = 720.0), leftover = null)
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { restoreLeftovers(any(), "e1") } returns restored
            coEvery { day(any()) } returns dayWith(restored)
        }
        val vm = viewModel(repo)

        vm.restoreFullPortion("e1")

        coVerify { repo.restoreLeftovers(date.toString(), "e1") }
        val row = vm.state.value.day!!.meals.single().entries.single()
        assertEquals(720.0, row.macros.caloriesKcal!!, 0.001)
        assertFalse(row.hasAppliedLeftover)
    }

    @Test
    fun `an error during apply surfaces and clears the saving flag`() = runTest {
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { applyLeftovers(any(), "e1") } throws RuntimeException("boom")
        }
        val vm = viewModel(repo)
        vm.reviewLeftovers(reviewEntry())

        vm.applyLeftovers("e1")

        assertFalse(vm.state.value.savingLeftover)
        assertEquals("boom", vm.state.value.error)
    }

    // --- withPendingOps leftover decoration + targetEntryId helper ---

    private fun leftoverOp() = NutritionOpEntity(
        id = "op1",
        type = NutritionOpType.REMOVE_LEFTOVERS.name,
        date = date.toString(),
        mealWire = "",
        clientEntryId = "cid",
        idempotencyKey = "op1",
        payloadJson = """{"targetEntryId":"e1"}""",
        jpegPath = "/tmp/x.jpg",
        label = "Analyzing leftovers…",
        attempts = 0,
        nextAttemptAt = 0,
        createdAt = 0,
    )

    @Test
    fun `targetEntryId reads the payload without moshi`() {
        assertEquals("e1", leftoverOp().targetEntryId())
    }

    @Test
    fun `withPendingOps decorates the target entry as ANALYZING without adding a row`() {
        val base = dayWith(reviewEntry().copy(leftover = null))
        val decorated = base.withPendingOps(listOf(leftoverOp()), date)!!
        val entries = decorated.meals.single().entries
        assertEquals(1, entries.size) // no synthetic row appended
        assertTrue(entries.single().isAnalyzingLeftovers)
    }

    @Test
    fun `withPendingOps leaves other entries untouched`() {
        val other = Entry("e2", "LUNCH", foodName = "Apple", quantity = 1.0, macros = Macros(95.0), source = "MANUAL")
        val day = NutritionDay(
            date = date.toString(),
            totals = Macros(815.0),
            meals = listOf(
                MealGroup("DINNER", Macros(720.0), listOf(reviewEntry().copy(leftover = null))),
                MealGroup("LUNCH", Macros(95.0), listOf(other)),
            ),
        )
        val decorated = day.withPendingOps(listOf(leftoverOp()), date)!!
        val lunch = decorated.meals.first { it.meal == "LUNCH" }.entries.single()
        assertFalse(lunch.isAnalyzingLeftovers)
    }
}
