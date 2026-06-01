package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.SavedStateHandle
import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GymDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val locationRepo: LocationRepository = mockk()
    private val equipmentRepo: EquipmentRepository = mockk()

    private fun handle() = SavedStateHandle(mapOf(WorkoutsRoutes.ARG_LOCATION_ID to "gym-1"))

    @Test
    fun `loads detail and fetches each equipment in parallel`() = runTest {
        val location = Fixtures.location(equipmentIds = listOf("eq-1", "eq-2"))
        coEvery { locationRepo.get("gym-1") } returns Result.success(location)
        coEvery { equipmentRepo.get("eq-1") } returns Result.success(Fixtures.equipment(id = "eq-1"))
        coEvery { equipmentRepo.get("eq-2") } returns Result.success(Fixtures.equipment(id = "eq-2"))

        val vm = GymDetailViewModel(locationRepo, equipmentRepo, handle())
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(2, state.equipment.size)
        coVerify { equipmentRepo.get("eq-1") }
        coVerify { equipmentRepo.get("eq-2") }
    }

    @Test
    fun `optimistic setDefault rolls back on failure`() = runTest {
        coEvery { locationRepo.get("gym-1") } returns Result.success(Fixtures.location(isDefault = false))
        coEvery { locationRepo.setDefault("gym-1") } returns Result.failure(RuntimeException("nope"))

        val vm = GymDetailViewModel(locationRepo, equipmentRepo, handle())
        advanceUntilIdle()

        vm.setDefault()
        // Optimistic flip happens synchronously before the suspend call resolves.
        assertTrue(vm.state.value.location!!.isDefault)

        advanceUntilIdle()
        // Rolled back after failure.
        assertFalse(vm.state.value.location!!.isDefault)
    }

    @Test
    fun `removeEquipment refreshes`() = runTest {
        coEvery { locationRepo.get("gym-1") } returns Result.success(Fixtures.location())
        coEvery { locationRepo.removeEquipment("gym-1", "eq-1") } returns Result.success(Unit)

        val vm = GymDetailViewModel(locationRepo, equipmentRepo, handle())
        advanceUntilIdle()

        vm.removeEquipment("eq-1")
        advanceUntilIdle()

        coVerify { locationRepo.removeEquipment("gym-1", "eq-1") }
        // get called once on init + once on refresh after remove.
        coVerify(exactly = 2) { locationRepo.get("gym-1") }
    }
}
