package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.medications.AdherenceRepository
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.DrugRepository
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultMedicationRepository @Inject constructor(
    private val api: MedicationsApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MedicationRepository {

    override suspend fun list(status: MedicationStatus?): List<Medication> = withContext(io) {
        api.list(status?.name).map(MedicationMapper::toDomain)
    }

    override suspend fun get(medicationId: String): MedicationDetail = withContext(io) {
        MedicationMapper.toDomain(api.get(medicationId))
    }

    override suspend fun create(request: CreateMedicationRequest): Medication = withContext(io) {
        MedicationMapper.toDomain(api.create(MedicationMapper.toDto(request)))
    }

    override suspend fun update(
        medicationId: String,
        request: UpdateMedicationRequest,
    ): Medication = withContext(io) {
        MedicationMapper.toDomain(api.update(medicationId, MedicationMapper.toDto(request)))
    }

    override suspend fun changeDose(
        medicationId: String,
        request: ChangeDoseRequest,
    ): Medication = withContext(io) {
        MedicationMapper.toDomain(api.changeDose(medicationId, MedicationMapper.toDto(request)))
    }

    override suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate?,
    ): Medication = withContext(io) {
        val body = DiscontinueDto(reason.name, notes, endDate)
        MedicationMapper.toDomain(api.discontinue(medicationId, body))
    }

    override suspend fun delete(medicationId: String) = withContext(io) {
        api.delete(medicationId)
    }

    override suspend fun todaysDoses(): List<TodaysDose> = withContext(io) {
        api.today().map(MedicationMapper::toDomain)
    }
}

@Singleton
internal class DefaultDrugRepository @Inject constructor(
    private val api: DrugsApi,
    private val lookupClient: DrugLookupStreamClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DrugRepository {

    override suspend fun catalog(query: String?): List<Drug> = withContext(io) {
        api.catalog(query).map(MedicationMapper::toDomain)
    }

    override suspend fun get(drugId: String): Drug = withContext(io) {
        MedicationMapper.toDomain(api.get(drugId))
    }

    override fun lookupStream(query: String): Flow<DrugLookupEvent> =
        lookupClient.stream(query).flowOn(io)
}

@Singleton
internal class DefaultAdherenceRepository @Inject constructor(
    private val api: AdherenceApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AdherenceRepository {

    override suspend fun logDose(
        medicationId: String,
        window: TimeWindow,
        takenAt: Instant,
        dose: Double?,
    ) = withContext(io) {
        // Backend uses server-side Instant.now() for takenAt; we still
        // accept a takenAt arg in the interface so the optimistic UI can
        // show "logged at 8:14" without a round trip. We don't ship it
        // over the wire — backend timestamps the dose log itself.
        api.log(medicationId, LogDoseDto(window = window.name, dose = dose))
    }

    override suspend fun undoDose(
        medicationId: String,
        date: LocalDate,
        window: TimeWindow,
    ) = withContext(io) {
        api.undo(medicationId, date.toString(), window.name)
    }
}
