package com.gte619n.healthfitness.feature.medical.list

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
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
class MedicationsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `loading then ready partitions by status`() = runTest(dispatcher) {
        val repo = FakeMedicationRepository(
            list = listOf(
                medFixture("a", MedicationStatus.ACTIVE),
                medFixture("b", MedicationStatus.DISCONTINUED),
                medFixture("c", MedicationStatus.ACTIVE),
            ),
        )
        val vm = MedicationsViewModel(repo)
        vm.state.test {
            // init triggers loading immediately
            assertEquals(MedicationsUiState.Loading, awaitItem())
            advanceUntilIdle()
            val ready = awaitItem() as MedicationsUiState.Ready
            assertEquals(2, ready.active.size)
            assertEquals(1, ready.discontinued.size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `failure surfaces error message`() = runTest(dispatcher) {
        val repo = FakeMedicationRepository(failure = IllegalStateException("boom"))
        val vm = MedicationsViewModel(repo)
        vm.state.test {
            assertEquals(MedicationsUiState.Loading, awaitItem())
            advanceUntilIdle()
            val err = awaitItem() as MedicationsUiState.Error
            assertTrue(err.message.contains("boom"))
            cancelAndConsumeRemainingEvents()
        }
    }
}

private fun medFixture(id: String, status: MedicationStatus): Medication = Medication(
    medicationId = id,
    drugId = null,
    drug = null,
    customName = "Med $id",
    status = status,
    dose = 100.0,
    unit = "mg",
    frequency = FrequencyConfig(FrequencyType.DAILY, timesPerPeriod = 1),
    timeSlots = emptyList(),
    protocolId = null,
    notes = null,
    prescribedBy = null,
    startDate = LocalDate.of(2026, 1, 1),
    endDate = null,
    discontinueReason = null,
    discontinueNotes = null,
    correlatedMarkers = emptyList(),
    adherence = null,
)

internal class FakeMedicationRepository(
    private val list: List<Medication> = emptyList(),
    private val failure: Throwable? = null,
    private val today: List<TodaysDose> = emptyList(),
) : MedicationRepository {
    var lastCreated: CreateMedicationRequest? = null
    var lastUpdated: Pair<String, UpdateMedicationRequest>? = null
    var lastDoseChanged: Pair<String, ChangeDoseRequest>? = null
    var lastDiscontinued: Triple<String, DiscontinueReason, String?>? = null
    var lastDeleted: String? = null

    override suspend fun list(status: MedicationStatus?): List<Medication> {
        failure?.let { throw it }
        return list.filter { status == null || it.status == status }
    }

    override suspend fun get(medicationId: String): MedicationDetail {
        val m = list.first { it.medicationId == medicationId }
        return MedicationDetail(m, emptyList())
    }

    override suspend fun create(request: CreateMedicationRequest): Medication {
        lastCreated = request
        return medFixture("new", MedicationStatus.ACTIVE)
    }

    override suspend fun update(medicationId: String, request: UpdateMedicationRequest): Medication {
        lastUpdated = medicationId to request
        return list.first { it.medicationId == medicationId }
    }

    override suspend fun changeDose(medicationId: String, request: ChangeDoseRequest): Medication {
        lastDoseChanged = medicationId to request
        return list.first { it.medicationId == medicationId }
    }

    override suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate?,
    ): Medication {
        lastDiscontinued = Triple(medicationId, reason, notes)
        return list.first { it.medicationId == medicationId }
            .copy(status = MedicationStatus.DISCONTINUED, discontinueReason = reason)
    }

    override suspend fun delete(medicationId: String) {
        lastDeleted = medicationId
    }

    override suspend fun todaysDoses(): List<TodaysDose> {
        failure?.let { throw it }
        return today
    }

    @Suppress("unused")
    private fun unused(): Instant = Instant.now()
}
