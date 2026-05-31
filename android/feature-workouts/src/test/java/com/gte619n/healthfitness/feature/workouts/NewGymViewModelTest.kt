package com.gte619n.healthfitness.feature.workouts

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.CreateLocationRequest
import com.gte619n.healthfitness.domain.workouts.HoursSlot
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.feature.workouts.ui.LocationFormState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewGymViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: LocationRepository = mockk()

    @Test
    fun `blank name is rejected`() = runTest {
        val vm = NewGymViewModel(repo)
        vm.update(LocationFormState(name = "", is24Hours = true))
        vm.submit { }
        advanceUntilIdle()

        assertEquals("Name is required", vm.form.value.error)
        coVerify(exactly = 0) { repo.create(any()) }
    }

    @Test
    fun `non-24h with no hours is rejected`() = runTest {
        val vm = NewGymViewModel(repo)
        vm.update(LocationFormState(name = "Gym", is24Hours = false))
        vm.submit { }
        advanceUntilIdle()

        assertEquals("Set at least one day's hours", vm.form.value.error)
        coVerify(exactly = 0) { repo.create(any()) }
    }

    @Test
    fun `valid payload reaches repository`() = runTest {
        val captured = slot<CreateLocationRequest>()
        coEvery { repo.create(capture(captured)) } returns Result.success(Fixtures.location(id = "new-1"))

        val vm = NewGymViewModel(repo)
        vm.update(
            LocationFormState(
                name = "Home Gym",
                address = "",
                is24Hours = false,
                hours = DayOfWeek.entries.associateWith { null } + (DayOfWeek.MON to HoursSlot("09:00", "17:00")),
                amenities = emptySet(),
            ),
        )

        var resultId: String? = null
        vm.submit { resultId = it }
        advanceUntilIdle()

        assertEquals("new-1", resultId)
        assertNull(vm.form.value.error)
        assertEquals("Home Gym", captured.captured.name)
        assertNull(captured.captured.address)
        assertEquals(setOf(DayOfWeek.MON), captured.captured.hours?.keys)
    }
}
