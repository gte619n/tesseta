package com.gte619n.healthfitness.feature.medical

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.feature.medical.today.TodaysDosesUiState
import com.gte619n.healthfitness.feature.medical.today.TodaysDosesViewModel
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodaysDosesViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `logs the dose and reflects the reactive source update`() = runTest {
        // Phase 1: the card is driven by the reactive observeTodaysDoses() source.
        // Toggling logs to the adherence mirror; the card updates when that source
        // re-emits (there is no manual optimistic flip to keep in sync).
        val meds = fakeMedicationRepository(doses = listOf(sampleDose(taken = false)))
        val source = MutableStateFlow(listOf(sampleDose(taken = false)))
        every { meds.observeTodaysDoses() } returns source
        val adherence = fakeAdherenceRepository()
        val vm = TodaysDosesViewModel(meds, adherence, SnackbarController())
        advanceUntilIdle()

        val before = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
        assertFalse(before.taken)

        vm.toggle(before)
        advanceUntilIdle()
        coVerify(exactly = 1) { adherence.logDose(any(), any(), any(), any()) }

        // Simulate the adherence mirror re-emitting the dose as taken.
        source.value = listOf(sampleDose(taken = true))
        advanceUntilIdle()
        assertTrue((vm.state.value as TodaysDosesUiState.Ready).doses.first().taken)
    }

    @Test
    fun `toggle failure shows error and leaves the source as truth`() = runTest {
        val meds = fakeMedicationRepository(doses = listOf(sampleDose(taken = false)))
        val source = MutableStateFlow(listOf(sampleDose(taken = false)))
        every { meds.observeTodaysDoses() } returns source
        val adherence = fakeAdherenceRepository(failOnLog = true)
        val snackbar = SnackbarController()
        val vm = TodaysDosesViewModel(meds, adherence, snackbar)
        advanceUntilIdle()

        snackbar.messages.test {
            val before = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
            vm.toggle(before)
            advanceUntilIdle()

            val message = awaitItem()
            assertTrue(message.isError)
            assertTrue(message.text.contains("try again"))
        }

        // The reactive source is the truth; a failed local write doesn't flip it.
        val current = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
        assertFalse(current.taken)
    }
}
