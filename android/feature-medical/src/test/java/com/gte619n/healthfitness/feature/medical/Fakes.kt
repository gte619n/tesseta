package com.gte619n.healthfitness.feature.medical

import com.gte619n.healthfitness.domain.medications.AdherenceRepository
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.DrugRepository
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.time.Instant
import java.time.LocalDate

internal fun sampleDrug(name: String = "Testosterone Cypionate") = Drug(
    drugId = "d1",
    name = name,
    category = DrugCategory.PRESCRIPTION,
    form = DrugForm.INJECTABLE_VIAL,
    defaultUnit = "mg",
    imageUrl = null,
    imageFallback = null,
)

internal fun sampleMedication(
    id: String = "m1",
    status: MedicationStatus = MedicationStatus.ACTIVE,
) = Medication(
    medicationId = id,
    drugId = "d1",
    drug = sampleDrug(),
    customName = null,
    status = status,
    dose = 200.0,
    unit = "mg",
    frequency = FrequencyConfig(FrequencyType.WEEKLY, timesPerPeriod = 1),
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

internal fun sampleDose(
    id: String = "m1",
    window: TimeWindow = TimeWindow.MORNING,
    taken: Boolean = false,
) = TodaysDose(
    medicationId = id,
    drugName = "Testosterone",
    window = window,
    dose = 200.0,
    unit = "mg",
    taken = taken,
    takenAt = null,
)

internal class FakeMedicationRepository(
    var meds: List<Medication> = emptyList(),
    var doses: List<TodaysDose> = emptyList(),
    var listError: Throwable? = null,
) : MedicationRepository {
    override suspend fun list(status: MedicationStatus?): List<Medication> {
        listError?.let { throw it }
        return if (status == null) meds else meds.filter { it.status == status }
    }

    override suspend fun get(medicationId: String): MedicationDetail =
        MedicationDetail(meds.first { it.medicationId == medicationId }, emptyList())

    override suspend fun create(request: CreateMedicationRequest): Medication = sampleMedication()
    override suspend fun update(medicationId: String, request: com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest): Medication = sampleMedication()
    override suspend fun changeDose(medicationId: String, request: ChangeDoseRequest): Medication = sampleMedication()
    override suspend fun discontinue(medicationId: String, reason: DiscontinueReason, notes: String?, endDate: LocalDate?): Medication =
        sampleMedication(status = MedicationStatus.DISCONTINUED)
    override suspend fun reactivate(medicationId: String, resumeDate: LocalDate?): Medication = sampleMedication()
    override suspend fun delete(medicationId: String) {}
    override suspend fun todaysDoses(): List<TodaysDose> = doses
}

internal class FakeAdherenceRepository(
    var failOnLog: Boolean = false,
) : AdherenceRepository {
    var logCount = 0
    var undoCount = 0

    override suspend fun logDose(medicationId: String, window: TimeWindow, takenAt: Instant, dose: Double?) {
        if (failOnLog) throw RuntimeException("network")
        logCount++
    }

    override suspend fun undoDose(medicationId: String, date: LocalDate, window: TimeWindow) {
        if (failOnLog) throw RuntimeException("network")
        undoCount++
    }
}

internal class FakeDrugRepository(
    private val events: List<DrugLookupEvent> = emptyList(),
    var catalog: List<Drug> = emptyList(),
) : DrugRepository {
    override suspend fun catalog(): List<Drug> = catalog
    override suspend fun get(drugId: String): Drug = sampleDrug()
    override fun lookupStream(query: String): Flow<DrugLookupEvent> = events.asFlow()
}
