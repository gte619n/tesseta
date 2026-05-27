package com.gte619n.healthfitness.mobile.dashboard

import com.gte619n.healthfitness.domain.medications.AdherenceRepository
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodaysDosesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val dose = TodaysDose(
        medicationId = "m1",
        drugName = "Test",
        imageUrl = null,
        window = TimeWindow.MORNING,
        dose = 200.0,
        unit = "mg",
        taken = false,
        takenAt = null,
    )

    @Test fun `loads doses on init`() = runTest(dispatcher) {
        val meds = StubMedications(today = listOf(dose))
        val adherence = StubAdherence()
        val vm = TodaysDosesViewModel(meds, adherence)
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state is TodaysDosesUiState.Ready)
        assertEquals(1, (state as TodaysDosesUiState.Ready).doses.size)
    }

    @Test fun `toggle optimistically flips and calls logDose`() = runTest(dispatcher) {
        val meds = StubMedications(today = listOf(dose))
        val adherence = StubAdherence()
        val vm = TodaysDosesViewModel(meds, adherence)
        advanceUntilIdle()

        vm.toggle(dose)

        // Before the suspending call resolves, the optimistic flip must already be visible.
        val firstReady = vm.state.value as TodaysDosesUiState.Ready
        assertTrue(firstReady.doses.first().taken)

        advanceUntilIdle()
        assertEquals(1, adherence.logged.size)
        assertEquals(TimeWindow.MORNING to "m1", adherence.logged.first())
    }

    @Test fun `failure reverts and surfaces error`() = runTest(dispatcher) {
        val meds = StubMedications(today = listOf(dose))
        val adherence = StubAdherence(shouldFail = true)
        val vm = TodaysDosesViewModel(meds, adherence)
        advanceUntilIdle()

        vm.toggle(dose)
        advanceUntilIdle()

        // The retry-after-failure refresh re-fetches the original list (taken=false).
        val readyAfterFail = vm.state.value as TodaysDosesUiState.Ready
        assertEquals(false, readyAfterFail.doses.first().taken)
        assertTrue(vm.errors.value?.contains("try again") == true)
    }
}

private class StubMedications(
    val today: List<TodaysDose> = emptyList(),
) : MedicationRepository {
    override suspend fun list(status: MedicationStatus?): List<Medication> = emptyList()
    override suspend fun get(medicationId: String): MedicationDetail =
        throw NotImplementedError()
    override suspend fun create(request: CreateMedicationRequest): Medication = throw NotImplementedError()
    override suspend fun update(medicationId: String, request: UpdateMedicationRequest): Medication =
        throw NotImplementedError()
    override suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate?,
    ): Medication = throw NotImplementedError()
    override suspend fun delete(medicationId: String) {}
    override suspend fun todaysDoses(): List<TodaysDose> = today
}

private class StubAdherence(val shouldFail: Boolean = false) : AdherenceRepository {
    val logged = mutableListOf<Pair<TimeWindow, String>>()
    val undone = mutableListOf<Triple<String, LocalDate, TimeWindow>>()

    override suspend fun logDose(
        medicationId: String,
        window: TimeWindow,
        takenAt: Instant,
        dose: Double?,
    ) {
        if (shouldFail) throw IllegalStateException("backend down")
        logged += window to medicationId
    }

    override suspend fun undoDose(medicationId: String, date: LocalDate, window: TimeWindow) {
        if (shouldFail) throw IllegalStateException("backend down")
        undone += Triple(medicationId, date, window)
    }
}
