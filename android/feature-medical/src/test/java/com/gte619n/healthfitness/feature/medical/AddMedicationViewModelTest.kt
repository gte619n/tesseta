package com.gte619n.healthfitness.feature.medical

import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.data.net.Connectivity
import com.gte619n.healthfitness.feature.medical.add.AddMedicationUiState
import com.gte619n.healthfitness.feature.medical.add.AddMedicationViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddMedicationViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    // IMPL-AND-20 (#41): the VM now takes Connectivity to gate the AI drug lookup.
    private fun onlineConnectivity(): Connectivity = mockk {
        every { isOnline } returns MutableStateFlow(true)
    }

    @Test
    fun `lookup collects progress then found advances to form`() = runTest {
        val events = listOf<DrugLookupEvent>(
            DrugLookupEvent.Progress("searching", "Searching…"),
            DrugLookupEvent.Progress("generating_image", "Generating image…"),
            DrugLookupEvent.Found(sampleDrug()),
        )
        val drugs = fakeDrugRepository(events = events)
        val vm = AddMedicationViewModel(drugs, fakeMedicationRepository(), onlineConnectivity())
        advanceUntilIdle()

        vm.startLookup("testosterone")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(AddMedicationUiState.Step.FORM, state.step)
        assertNotNull(state.selectedDrug)
        assertEquals("Testosterone Cypionate", state.selectedDrug?.name)
    }

    @Test
    fun `not found keeps search step`() = runTest {
        val drugs = fakeDrugRepository(events = listOf(DrugLookupEvent.NotFound("no match")))
        val vm = AddMedicationViewModel(drugs, fakeMedicationRepository(), onlineConnectivity())
        advanceUntilIdle()

        vm.startLookup("zzzz")
        advanceUntilIdle()

        assertEquals(AddMedicationUiState.Step.SEARCH, vm.state.value.step)
        assertTrue(vm.state.value.lookupEvent is DrugLookupEvent.NotFound)
    }

    @Test
    fun `submit success invokes onDone`() = runTest {
        val vm = AddMedicationViewModel(fakeDrugRepository(), fakeMedicationRepository(), onlineConnectivity())
        advanceUntilIdle()

        var done = false
        vm.submit(
            CreateMedicationRequest(
                drugId = "d1",
                dose = 200.0,
                unit = "mg",
                frequency = FrequencyConfig(FrequencyType.WEEKLY, timesPerPeriod = 1),
                timeSlots = emptyList(),
            ),
        ) { done = true }
        advanceUntilIdle()

        assertTrue(done)
    }

    @Test
    fun `submit failure surfaces error`() = runTest {
        val failingRepo = fakeMedicationRepository().apply {
            coEvery { create(any()) } throws RuntimeException("save failed")
        }
        val vm = AddMedicationViewModel(fakeDrugRepository(), failingRepo, onlineConnectivity())
        advanceUntilIdle()

        var done = false
        vm.submit(
            CreateMedicationRequest(
                dose = 1.0,
                unit = "mg",
                frequency = FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 1),
                timeSlots = emptyList(),
            ),
        ) { done = true }
        advanceUntilIdle()

        assertTrue(!done)
        assertTrue(vm.state.value.error!!.contains("save failed"))
    }
}
