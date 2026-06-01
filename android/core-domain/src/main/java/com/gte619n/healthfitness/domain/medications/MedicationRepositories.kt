package com.gte619n.healthfitness.domain.medications

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

interface MedicationRepository {
    suspend fun list(status: MedicationStatus? = null): List<Medication>
    suspend fun get(medicationId: String): MedicationDetail
    suspend fun create(request: CreateMedicationRequest): Medication
    suspend fun update(medicationId: String, request: UpdateMedicationRequest): Medication

    /** [PR#8] Change dose effective on a date; backend closes the open period + opens a new one. */
    suspend fun changeDose(medicationId: String, request: ChangeDoseRequest): Medication

    suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate? = null,
    ): Medication

    /** [PR#8] Resume a discontinued medication from [resumeDate] (defaults to today server-side). */
    suspend fun reactivate(medicationId: String, resumeDate: LocalDate? = null): Medication

    suspend fun delete(medicationId: String)
    suspend fun todaysDoses(): List<TodaysDose>
}

interface DrugRepository {
    suspend fun catalog(): List<Drug>
    suspend fun get(drugId: String): Drug

    /** SSE stream emitting phase updates: searching, found, generating_image, complete, not_found, failed. */
    fun lookupStream(query: String): Flow<DrugLookupEvent>
}

sealed interface DrugLookupEvent {
    data class Progress(val phase: String, val message: String?) : DrugLookupEvent
    data class Found(val drug: Drug) : DrugLookupEvent
    data class NotFound(val message: String?) : DrugLookupEvent
    data class Failed(val error: String) : DrugLookupEvent
}

interface AdherenceRepository {
    /** Log a dose taken at [takenAt] (default now) for [window]. */
    suspend fun logDose(
        medicationId: String,
        window: TimeWindow,
        takenAt: Instant = Instant.now(),
        dose: Double? = null,
    )

    suspend fun undoDose(medicationId: String, date: LocalDate, window: TimeWindow)
}
