package com.gte619n.healthfitness.shared.presentation.medications

import com.gte619n.healthfitness.shared.data.AdherenceRepository
import com.gte619n.healthfitness.shared.data.MedicationCrudRepository
import com.gte619n.healthfitness.shared.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.shared.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.shared.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.shared.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.shared.domain.medications.FrequencyType
import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.domain.medications.MedicationDetail
import com.gte619n.healthfitness.shared.domain.medications.MedicationStatus
import com.gte619n.healthfitness.shared.domain.medications.TimeSlot
import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import com.gte619n.healthfitness.shared.domain.medications.TodaysDose
import com.gte619n.healthfitness.shared.domain.medications.UpdateMedicationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave B — commonTest fixtures for the medications VMs.
 * Mirrors the Android `feature-medical` test fakes but as plain KMP fakes (no
 * MockK). These author the vertical against interfaces before the concrete
 * Room/Ktor store lands (Phase 1C).
 */

fun sampleMedication(
    id: String = "med-1",
    name: String = "Vitamin D",
    status: MedicationStatus = MedicationStatus.ACTIVE,
    dose: Double = 1.0,
    unit: String = "tab",
    frequency: FrequencyConfig = FrequencyConfig(type = FrequencyType.DAILY),
    timeSlots: List<TimeSlot> = listOf(TimeSlot(TimeWindow.MORNING, dose)),
    startDate: LocalDate = LocalDate(2026, 1, 1),
    endDate: LocalDate? = null,
): Medication = Medication(
    medicationId = id,
    drugId = null,
    drug = null,
    customName = name,
    status = status,
    dose = dose,
    unit = unit,
    frequency = frequency,
    timeSlots = timeSlots,
    protocolId = null,
    notes = null,
    prescribedBy = null,
    startDate = startDate,
    endDate = endDate,
    discontinueReason = null,
    discontinueNotes = null,
    correlatedMarkers = emptyList(),
    adherence = null,
)

fun sampleDose(
    id: String = "med-1",
    name: String = "Vitamin D",
    window: TimeWindow = TimeWindow.MORNING,
    taken: Boolean = false,
): TodaysDose = TodaysDose(
    medicationId = id,
    drugName = name,
    window = window,
    dose = 1.0,
    unit = "tab",
    taken = taken,
    takenAt = null,
)

/** Records adherence writes; the doses source is driven externally by the test. */
class FakeAdherenceRepository(
    private val failOnLog: Boolean = false,
) : AdherenceRepository {
    var logCount = 0; private set
    var undoCount = 0; private set
    var missedCount = 0; private set
    val logged = mutableListOf<Pair<String, TimeWindow>>()

    override suspend fun logDose(medicationId: String, window: TimeWindow) {
        if (failOnLog) throw RuntimeException("offline")
        logCount++
        logged += medicationId to window
    }

    override suspend fun undoDose(medicationId: String, date: LocalDate, window: TimeWindow) {
        undoCount++
    }

    override suspend fun markMissed(medicationId: String, date: LocalDate, window: TimeWindow, dose: Double) {
        missedCount++
    }

    override suspend fun takenWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>> = emptySet()
    override suspend fun recordedWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>> = emptySet()
}

/** Fake CRUD repo whose today-projection is a hot [MutableStateFlow] the test mutates. */
class FakeMedicationCrudRepository(
    val doses: MutableStateFlow<List<TodaysDose>> = MutableStateFlow(emptyList()),
    private val meds: List<Medication> = emptyList(),
    private val refreshThrows: Boolean = false,
) : MedicationCrudRepository {
    var refreshCount = 0; private set

    override fun observeTodaysDoses(): Flow<List<TodaysDose>> = doses
    override suspend fun refreshTodaysDoses() {
        refreshCount++
        if (refreshThrows) throw RuntimeException("offline")
    }
    override suspend fun cachedTodaysDoses(): List<TodaysDose> = doses.value
    override suspend fun list(status: MedicationStatus): List<Medication> = meds.filter { it.status == status }
    override suspend fun create(request: CreateMedicationRequest): Medication = sampleMedication()
    override suspend fun get(medicationId: String): MedicationDetail =
        MedicationDetail(sampleMedication(id = medicationId), emptyList())
    override suspend fun cachedDetail(medicationId: String): MedicationDetail =
        MedicationDetail(sampleMedication(id = medicationId), emptyList())
    override suspend fun update(medicationId: String, request: UpdateMedicationRequest) {}
    override suspend fun changeDose(medicationId: String, request: ChangeDoseRequest) {}
    override suspend fun discontinue(medicationId: String, reason: DiscontinueReason, notes: String?, endDate: LocalDate) {}
    override suspend fun reactivate(medicationId: String, resumeDate: LocalDate?) {}
    override suspend fun delete(medicationId: String) {}
}
