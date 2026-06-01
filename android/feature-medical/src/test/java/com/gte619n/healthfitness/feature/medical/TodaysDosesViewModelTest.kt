package com.gte619n.healthfitness.feature.medical

import app.cash.turbine.test
import com.gte619n.healthfitness.feature.medical.today.TodaysDosesUiState
import com.gte619n.healthfitness.feature.medical.today.TodaysDosesViewModel
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodaysDosesViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `toggle optimistically flips and logs`() = runTest {
        val meds = FakeMedicationRepository(doses = listOf(sampleDose(taken = false)))
        val adherence = FakeAdherenceRepository()
        val vm = TodaysDosesViewModel(meds, adherence, SnackbarController())
        advanceUntilIdle()

        val before = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
        vm.toggle(before)
        // Optimistic flip is synchronous (before the suspend call runs).
        val flipped = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
        assertTrue(flipped.taken)

        advanceUntilIdle()
        assertEquals(1, adherence.logCount)
    }

    @Test
    fun `toggle failure reverts and shows error`() = runTest {
        val meds = FakeMedicationRepository(doses = listOf(sampleDose(taken = false)))
        val adherence = FakeAdherenceRepository(failOnLog = true)
        val snackbar = SnackbarController()
        val vm = TodaysDosesViewModel(meds, adherence, snackbar)
        advanceUntilIdle()

        // Collect snackbar messages while triggering the failing toggle.
        snackbar.messages.test {
            val before = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
            vm.toggle(before)
            advanceUntilIdle()

            val message = awaitItem()
            assertTrue(message.isError)
            assertTrue(message.text.contains("try again"))
        }

        // After failure, refresh() re-fetches truth (taken = false).
        val reverted = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
        assertFalse(reverted.taken)
    }
}
