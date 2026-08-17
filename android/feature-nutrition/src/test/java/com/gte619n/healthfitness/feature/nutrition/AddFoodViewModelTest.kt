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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        coEvery { this@mockk.searchMeals(any()) } coAnswers { searchMeals() }
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
