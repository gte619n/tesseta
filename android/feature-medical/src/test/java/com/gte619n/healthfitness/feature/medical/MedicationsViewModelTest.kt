package com.gte619n.healthfitness.feature.medical

import app.cash.turbine.test
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.feature.medical.list.MedicationsUiState
import com.gte619n.healthfitness.feature.medical.list.MedicationsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MedicationsViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `loads and partitions active and discontinued`() = runTest {
        val repo = fakeMedicationRepository(
            meds = listOf(
                sampleMedication("a", MedicationStatus.ACTIVE),
                sampleMedication("b", MedicationStatus.DISCONTINUED),
            ),
        )
        val vm = MedicationsViewModel(repo)

        vm.state.test {
            // Brief initial Loading, then the reactive mirror emission.
            assertEquals(MedicationsUiState.Loading, awaitItem())
            val ready = awaitItem() as MedicationsUiState.Ready
            assertEquals(1, ready.active.size)
            assertEquals(1, ready.discontinued.size)
            assertEquals("a", ready.active.first().medicationId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `error path surfaces message`() = runTest {
        val repo = fakeMedicationRepository(listError = RuntimeException("boom"))
        val vm = MedicationsViewModel(repo)

        vm.state.test {
            assertEquals(MedicationsUiState.Loading, awaitItem())
            val error = awaitItem() as MedicationsUiState.Error
            assertTrue(error.message.contains("boom"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reactive stream reflects mirror updates`() = runTest {
        // A live mirror stream: pushing a new value updates the UI in place, with no
        // Loading reset — the offline-first behaviour the screen relies on.
        val meds = MutableStateFlow(listOf(sampleMedication("a")))
        val repo = mockk<MedicationRepository>()
        every { repo.observe() } returns meds
        coEvery { repo.refresh() } returns Unit
        val vm = MedicationsViewModel(repo)

        vm.state.test {
            assertEquals(MedicationsUiState.Loading, awaitItem())
            assertEquals(1, (awaitItem() as MedicationsUiState.Ready).active.size)

            meds.value = listOf(sampleMedication("a"), sampleMedication("c"))
            assertEquals(2, (awaitItem() as MedicationsUiState.Ready).active.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
