package com.gte619n.healthfitness.feature.medical

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.feature.medical.list.MedicationsUiState
import com.gte619n.healthfitness.feature.medical.list.MedicationsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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
        val repo = FakeMedicationRepository(
            meds = listOf(
                sampleMedication("a", MedicationStatus.ACTIVE),
                sampleMedication("b", MedicationStatus.DISCONTINUED),
            ),
        )
        val vm = MedicationsViewModel(repo)

        vm.state.test {
            assertEquals(MedicationsUiState.Loading, awaitItem())
            advanceUntilIdle()
            val ready = awaitItem() as MedicationsUiState.Ready
            assertEquals(1, ready.active.size)
            assertEquals(1, ready.discontinued.size)
            assertEquals("a", ready.active.first().medicationId)
        }
    }

    @Test
    fun `error path surfaces message`() = runTest {
        val repo = FakeMedicationRepository(listError = RuntimeException("boom"))
        val vm = MedicationsViewModel(repo)

        vm.state.test {
            assertEquals(MedicationsUiState.Loading, awaitItem())
            advanceUntilIdle()
            val error = awaitItem() as MedicationsUiState.Error
            assertTrue(error.message.contains("boom"))
        }
    }

    @Test
    fun `refresh reloads`() = runTest {
        val repo = FakeMedicationRepository(meds = listOf(sampleMedication("a")))
        val vm = MedicationsViewModel(repo)
        advanceUntilIdle()

        repo.meds = listOf(sampleMedication("a"), sampleMedication("c"))
        vm.refresh()
        advanceUntilIdle()

        val ready = vm.state.value as MedicationsUiState.Ready
        assertEquals(2, ready.active.size)
    }
}
