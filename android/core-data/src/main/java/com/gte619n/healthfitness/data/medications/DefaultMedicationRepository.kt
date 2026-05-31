package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultMedicationRepository @Inject constructor(
    private val api: MedicationsApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MedicationRepository {

    override suspend fun list(status: MedicationStatus?): List<Medication> = withContext(io) {
        api.list(status?.name).map { MedicationMapper.toDomain(it) }
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
        val body = DiscontinueDto(reason = reason.name, notes = notes, endDate = endDate)
        MedicationMapper.toDomain(api.discontinue(medicationId, body))
    }

    override suspend fun reactivate(
        medicationId: String,
        resumeDate: LocalDate?,
    ): Medication = withContext(io) {
        MedicationMapper.toDomain(api.reactivate(medicationId, ReactivateDto(resumeDate = resumeDate)))
    }

    override suspend fun delete(medicationId: String) = withContext(io) {
        api.delete(medicationId)
    }

    override suspend fun todaysDoses(): List<TodaysDose> = withContext(io) {
        api.today().map { MedicationMapper.toDomain(it) }
    }
}
