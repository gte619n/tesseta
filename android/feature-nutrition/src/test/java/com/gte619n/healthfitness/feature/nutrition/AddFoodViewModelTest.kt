package com.gte619n.healthfitness.feature.nutrition

import com.gte619n.healthfitness.data.nutrition.FoodRepository
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddFoodViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleFood(id: String) = Food(
        foodId = id,
        name = "Chicken breast",
        macrosPer100g = Macros(caloriesKcal = 165.0),
        source = "USER",
        status = "CONFIRMED",
        imageStatus = "NONE",
    )

    private fun viewModel(
        foods: FoodRepository,
        nutrition: NutritionRepository,
    ) = AddFoodViewModel(foods, nutrition)

    // Recents load in init{}; every test stubs it so the VM constructs cleanly.
    private fun nutritionRepo(
        searchMeals: suspend () -> List<com.gte619n.healthfitness.domain.nutrition.MealSearchResult> = { emptyList() },
    ): NutritionRepository = mockk {
        coEvery { recentMeals(any()) } returns emptyList()
        coEvery { cachedRecentMeals(any()) } returns emptyList()
        coEvery { cachedSearchMeals(any(), any()) } returns emptyList()
        coEvery { this@mockk.searchMeals(any()) } coAnswers { searchMeals() }
    }

    /**
     * local-first: the cached (local) catalog hits must render on the SAME frame
     * the user types — before the 220ms debounce and the network call — then the
     * authoritative network result replaces them. Guards the whole point of the
     * redesign: search never blocks on the network for foods already on device.
     */
    @Test
    fun localResultsShowImmediatelyThenNetworkReplacesThem() = runTest {
        val local = sampleFood("local")
        val net = sampleFood("net")
        val foods = mockk<FoodRepository> {
            coEvery { localSearch(any(), any()) } returns listOf(local)
            coEvery { search(any()) } returns listOf(net)
        }
        val vm = viewModel(foods, nutritionRepo())

        vm.onQueryChange("chicken")
        runCurrent() // run the instant local pass, but NOT the 220ms debounce

        assertEquals(listOf(local), vm.state.value.results)
        assertTrue(vm.state.value.searching)

        advanceUntilIdle() // clear the debounce + let the network pass settle

        assertEquals(listOf(net), vm.state.value.results)
        assertFalse(vm.state.value.searching)
    }

    /**
     * Regression: a failing catalog food search must surface as an error state,
     * NOT force-close the app. The search runs the meal and food lookups as two
     * `async` children; before the supervisorScope fix a throw from the foods
     * child propagated up the Job hierarchy past the try/catch and crashed.
     */
    @Test
    fun foodSearchFailureSurfacesErrorInsteadOfCrashing() = runTest {
        val foods = mockk<FoodRepository> {
            coEvery { search(any()) } throws RuntimeException("network down")
        }
        val vm = viewModel(foods, nutritionRepo())

        vm.onQueryChange("chicken")
        advanceUntilIdle() // clear the 220ms debounce + let the search settle

        assertEquals("network down", vm.state.value.error)
        assertFalse(vm.state.value.searching)
    }

    /**
     * The meal search failing on its own leaves that group empty but still shows
     * the catalog foods — it must never surface an error or crash.
     */
    @Test
    fun mealSearchFailureStillReturnsCatalogFoods() = runTest {
        val foods = mockk<FoodRepository> {
            coEvery { search(any()) } returns listOf(sampleFood("f1"))
        }
        val nutrition = nutritionRepo(searchMeals = { throw RuntimeException("meals down") })
        val vm = viewModel(foods, nutrition)

        vm.onQueryChange("chicken")
        advanceUntilIdle()

        assertEquals(listOf(sampleFood("f1")), vm.state.value.results)
        assertEquals(null, vm.state.value.error)
        assertFalse(vm.state.value.searching)
    }
}
