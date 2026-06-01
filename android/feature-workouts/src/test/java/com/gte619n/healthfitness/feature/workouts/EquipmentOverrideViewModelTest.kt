package com.gte619n.healthfitness.feature.workouts

import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentOverrideViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val locationRepo: LocationRepository = mockk()
    private val equipmentRepo: EquipmentRepository = mockk()

    @Test
    fun `load hydrates from catalog default when no existing override`() = runTest {
        val equipment = Fixtures.equipment(
            id = "eq-1",
            schema = SpecSchemaTag.SELECTORIZED,
            specs = EquipmentSpec.Selectorized(10.0, 200.0, 5.0),
        )
        coEvery { equipmentRepo.get("eq-1") } returns Result.success(equipment)
        coEvery { locationRepo.get("gym-1") } returns Result.success(Fixtures.location())

        val vm = EquipmentOverrideViewModel(locationRepo, equipmentRepo)
        vm.load("gym-1", "eq-1")
        advanceUntilIdle()

        val specs = vm.state.value.specs
        assertEquals(10.0, specs["minWeight"])
        assertEquals(200.0, specs["maxWeight"])
        assertEquals(5.0, specs["increment"])
    }

    @Test
    fun `existing override overlays catalog default`() = runTest {
        val equipment = Fixtures.equipment(
            id = "eq-1",
            schema = SpecSchemaTag.SELECTORIZED,
            specs = EquipmentSpec.Selectorized(10.0, 200.0, 5.0),
        )
        coEvery { equipmentRepo.get("eq-1") } returns Result.success(equipment)
        coEvery { locationRepo.get("gym-1") } returns Result.success(
            Fixtures.location(equipmentSpecs = mapOf("eq-1" to mapOf("maxWeight" to 150.0))),
        )

        val vm = EquipmentOverrideViewModel(locationRepo, equipmentRepo)
        vm.load("gym-1", "eq-1")
        advanceUntilIdle()

        assertEquals(150.0, vm.state.value.specs["maxWeight"])
        assertEquals(10.0, vm.state.value.specs["minWeight"])
    }

    @Test
    fun `save sends edited specs map`() = runTest {
        val equipment = Fixtures.equipment(id = "eq-1", schema = SpecSchemaTag.SELECTORIZED)
        coEvery { equipmentRepo.get("eq-1") } returns Result.success(equipment)
        coEvery { locationRepo.get("gym-1") } returns Result.success(Fixtures.location())
        val captured = slot<Map<String, Any?>>()
        coEvery { locationRepo.updateEquipmentSpecs("gym-1", "eq-1", capture(captured)) } returns
            Result.success(Fixtures.location())

        val vm = EquipmentOverrideViewModel(locationRepo, equipmentRepo)
        vm.load("gym-1", "eq-1")
        advanceUntilIdle()
        vm.update(mapOf("maxWeight" to 175.0))

        var done = false
        vm.save("gym-1", "eq-1") { done = true }
        advanceUntilIdle()

        assertEquals(true, done)
        assertEquals(175.0, captured.captured["maxWeight"])
        coVerify { locationRepo.updateEquipmentSpecs("gym-1", "eq-1", any()) }
    }
}
