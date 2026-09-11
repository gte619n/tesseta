package com.gte619n.healthfitness.feature.settings.drinks

import com.gte619n.healthfitness.data.nutrition.DrinkRepository
import com.gte619n.healthfitness.domain.nutrition.AlcoholInfo
import com.gte619n.healthfitness.domain.nutrition.DrinkProposal
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo: DrinkRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.listMyDrinks() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DrinkSettingsViewModel(repo)

    private fun drink(id: String, name: String) = Food(
        foodId = id,
        name = name,
        category = "drink",
        macrosPer100g = Macros.EMPTY,
        source = "USER",
        status = "ACTIVE",
        imageStatus = "READY",
        alcohol = AlcoholInfo(abvPercent = 5.0, servingVolumeMl = 355.0, standardDrinks = 1.4),
        servingMacros = Macros(caloriesKcal = 150.0, carbsGrams = 13.0, sugarGrams = 1.0),
    )

    @Test
    fun `canSave requires name abv and volume`() = runTest {
        val model = vm()
        model.openAdd()
        assertFalse(model.state.value.editor!!.canSave)

        model.updateEditor { it.copy(name = "IPA") }
        assertFalse("still missing abv/volume", model.state.value.editor!!.canSave)

        model.updateEditor { it.copy(abvPercent = "6.5", servingVolumeMl = "355") }
        assertTrue(model.state.value.editor!!.canSave)

        // Non-numeric abv is not saveable.
        model.updateEditor { it.copy(abvPercent = "abc") }
        assertFalse(model.state.value.editor!!.canSave)
    }

    @Test
    fun `analyze 422 unavailable flips to manual entry`() = runTest {
        coEvery { repo.analyze(any()) } returns DrinkRepository.AnalyzeResult.Unavailable
        val model = vm()
        model.openAdd()
        model.updateEditor { it.copy(name = "Mystery cocktail") }

        model.analyze()

        val editor = model.state.value.editor!!
        assertTrue(editor.analyzeUnavailable)
        assertFalse(editor.analyzing)
    }

    @Test
    fun `analyze success prefills abv volume and mixer macros`() = runTest {
        coEvery { repo.analyze(any()) } returns DrinkRepository.AnalyzeResult.Success(
            DrinkProposal(
                name = "House Red",
                abvPercent = 13.5,
                servingVolumeMl = 150.0,
                servingMacros = Macros(caloriesKcal = 125.0, carbsGrams = 4.0, sugarGrams = 1.0),
                alcohol = AlcoholInfo(alcoholGrams = 16.0, standardDrinks = 1.3),
            ),
        )
        val model = vm()
        model.openAdd()
        model.updateEditor { it.copy(name = "House Red") }

        model.analyze()

        val editor = model.state.value.editor!!
        assertEquals("13.5", editor.abvPercent)
        assertEquals("150", editor.servingVolumeMl)
        assertEquals("4", editor.carbsGrams)
        assertEquals(1.3, editor.derivedStandardDrinks!!, 0.001)
    }

    @Test
    fun `save create passes parsed abv volume and mixer macros`() = runTest {
        coEvery {
            repo.createDrink(any(), any(), any(), any(), any())
        } returns drink("new1", "IPA")
        val model = vm()
        model.openAdd()
        model.updateEditor {
            it.copy(name = "IPA", abvPercent = "6.5", servingVolumeMl = "473", carbsGrams = "17")
        }

        model.save()

        val macros = mutableListOf<Macros?>()
        coVerify {
            repo.createDrink("IPA", 6.5, 473.0, null, captureNullable(macros))
        }
        assertEquals(17.0, macros.single()!!.carbsGrams!!, 0.001)
        // Editor closes on success.
        assertNull(model.state.value.editor)
    }

    @Test
    fun `moveUp reorders the list optimistically and persists the new order`() = runTest {
        val existing = listOf(drink("a", "Lager"), drink("b", "Stout"), drink("c", "IPA"))
        coEvery { repo.listMyDrinks() } returns existing
        coEvery { repo.reorder(any()) } returns Unit
        val model = vm()

        model.moveUp(existing[2]) // move "c" (index 2) up one → a, c, b

        assertEquals(listOf("a", "c", "b"), model.state.value.drinks.map { it.foodId })
        coVerify { repo.reorder(listOf("a", "c", "b")) }
    }

    @Test
    fun `moveDown reorders the list optimistically and persists the new order`() = runTest {
        val existing = listOf(drink("a", "Lager"), drink("b", "Stout"), drink("c", "IPA"))
        coEvery { repo.listMyDrinks() } returns existing
        coEvery { repo.reorder(any()) } returns Unit
        val model = vm()

        model.moveDown(existing[0]) // move "a" (index 0) down one → b, a, c

        assertEquals(listOf("b", "a", "c"), model.state.value.drinks.map { it.foodId })
        coVerify { repo.reorder(listOf("b", "a", "c")) }
    }

    @Test
    fun `moveUp on the first drink is a no-op`() = runTest {
        val existing = listOf(drink("a", "Lager"), drink("b", "Stout"))
        coEvery { repo.listMyDrinks() } returns existing
        val model = vm()

        model.moveUp(existing[0])

        assertEquals(listOf("a", "b"), model.state.value.drinks.map { it.foodId })
        coVerify(exactly = 0) { repo.reorder(any()) }
    }

    @Test
    fun `swapped swaps two indices and is bounds-safe`() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").swapped(0, 1))
        // Out-of-range index leaves the list unchanged.
        assertEquals(listOf("a", "b"), listOf("a", "b").swapped(0, 5))
    }

    @Test
    fun `archive removes the drink from the list`() = runTest {
        val existing = listOf(drink("a", "Lager"), drink("b", "Stout"))
        coEvery { repo.listMyDrinks() } returns existing
        coEvery { repo.archiveDrink("a") } returns Unit
        val model = vm()
        assertEquals(2, model.state.value.drinks.size)

        model.archive(existing[0])

        val ids = model.state.value.drinks.map { it.foodId }
        assertEquals(listOf("b"), ids)
    }
}
