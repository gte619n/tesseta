package com.gte619n.healthfitness.feature.nutrition

import com.gte619n.healthfitness.data.nutrition.NutritionOpStore
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.data.sync.SyncSignals
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryIngredient
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
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * IMPL-FIXPACK-01 Phase 2: removing a background-artefact ingredient is an
 * immediate, offline-first action; the snackbar Undo restores it. These cover the
 * ViewModel's remove/undo bookkeeping (the repo re-sum itself is proven in
 * NutritionRepositoryLogMealTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionRemoveIngredientViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val date = LocalDate.now()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val stray = EntryIngredient(name = "Napkin", macros = Macros(caloriesKcal = 200.0))

    private fun entry(kcal: Double, ingredients: List<EntryIngredient>) = Entry(
        entryId = "e1",
        meal = "DINNER",
        foodName = "Salmon plate",
        quantity = 1.0,
        macros = Macros(caloriesKcal = kcal),
        source = "PHOTO",
        ingredients = ingredients,
    )

    private fun dayWith(e: Entry) = NutritionDay(
        date = date.toString(),
        totals = e.macros,
        meals = listOf(MealGroup(e.meal, e.macros, listOf(e))),
    )

    private fun viewModel(repo: NutritionRepository): NutritionTodayViewModel {
        val ops = mockk<NutritionOpStore> { coEvery { observeAll() } returns flowOf(emptyList()) }
        return NutritionTodayViewModel(repo, ops, SyncSignals())
    }

    @Test
    fun `removeIngredient persists and drops the day total on the open sheet`() = runTest {
        val afterRemove = entry(300.0, listOf(EntryIngredient(name = "Salmon", macros = Macros(caloriesKcal = 300.0))))
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { removeIngredient(date.toString(), "e1", 1) } returns
                NutritionRepository.RemovedIngredient(afterRemove, stray, 1)
            coEvery { day(any()) } returns dayWith(afterRemove)
        }
        val vm = viewModel(repo)

        vm.removeIngredient("e1", 1)

        coVerify { repo.removeIngredient(date.toString(), "e1", 1) }
        assertEquals("e1", vm.state.value.editingComposite?.entryId)
        assertEquals(300.0, vm.state.value.day!!.totals.caloriesKcal!!, 0.0001)
    }

    @Test
    fun `undo restores the removed ingredient via the repo`() = runTest {
        val salmon = EntryIngredient(name = "Salmon", macros = Macros(caloriesKcal = 300.0))
        val afterRemove = entry(300.0, listOf(salmon))
        val afterRestore = entry(500.0, listOf(salmon, stray))
        val repo = mockk<NutritionRepository>(relaxed = true) {
            coEvery { removeIngredient(date.toString(), "e1", 1) } returns
                NutritionRepository.RemovedIngredient(afterRemove, stray, 1)
            coEvery { restoreIngredient(date.toString(), "e1", 1, stray) } returns afterRestore
            coEvery { day(any()) } returnsMany listOf(dayWith(afterRemove), dayWith(afterRestore))
        }
        val vm = viewModel(repo)

        vm.removeIngredient("e1", 1)
        vm.undoRemoveIngredient()

        coVerify { repo.restoreIngredient(date.toString(), "e1", 1, stray) }
        assertEquals(500.0, vm.state.value.day!!.totals.caloriesKcal!!, 0.0001)
    }

    @Test
    fun `undo is a no-op when nothing was removed`() = runTest {
        val repo = mockk<NutritionRepository>(relaxed = true)
        val vm = viewModel(repo)

        vm.undoRemoveIngredient()

        coVerify(exactly = 0) { repo.restoreIngredient(any(), any(), any(), any()) }
    }
}
