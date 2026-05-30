package com.gte619n.healthfitness.domain.medications

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Backend-facing repository for the user's medications. Implementations
 * live in `core-data/medications/` and map wire-DTOs to the domain models
 * declared above on the IO dispatcher.
 *
 * `discontinue` and `delete` are intentionally separate calls — the
 * backend exposes them as distinct endpoints (`POST .../discontinue` vs
 * `DELETE`).
 */
interface MedicationRepository {
    suspend fun list(status: MedicationStatus? = null): List<Medication>
    suspend fun get(medicationId: String): MedicationDetail
    suspend fun create(request: CreateMedicationRequest): Medication
    suspend fun update(medicationId: String, request: UpdateMedicationRequest): Medication
    suspend fun changeDose(medicationId: String, request: ChangeDoseRequest): Medication
    suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate? = null,
    ): Medication
    suspend fun delete(medicationId: String)
    suspend fun todaysDoses(): List<TodaysDose>
}

/**
 * Shared drug catalog + AI-assisted lookup. `lookupStream` opens an SSE
 * connection to `POST /api/drugs/lookup/stream` and emits phase events as
 * the backend search → image-generation pipeline progresses.
 */
interface DrugRepository {
    suspend fun catalog(query: String? = null): List<Drug>
    suspend fun get(drugId: String): Drug

    /**
     * SSE stream of phase updates: emits one or more [DrugLookupEvent.Progress]
     * events, then a terminal [DrugLookupEvent.Found], [DrugLookupEvent.NotFound]
     * or [DrugLookupEvent.Failed]. The flow completes after the terminal
     * event.
     */
    fun lookupStream(query: String): Flow<DrugLookupEvent>
}

/** Events emitted by `DrugRepository.lookupStream`. */
sealed interface DrugLookupEvent {
    /** Non-terminal: a phase message ("searching", "generating_image", etc.). */
    data class Progress(val phase: String, val message: String?) : DrugLookupEvent

    /** Terminal: the backend found a drug and (optionally) generated an image. */
    data class Found(val drug: Drug) : DrugLookupEvent

    /** Terminal: the backend found no match. */
    data class NotFound(val message: String?) : DrugLookupEvent

    /** Terminal: the lookup failed (network error, server error, etc.). */
    data class Failed(val error: String) : DrugLookupEvent
}

/**
 * Per-window adherence logging. Optimistic UI flips locally first, then
 * calls `logDose` / `undoDose`; on failure the caller re-fetches truth via
 * `MedicationRepository.todaysDoses()`.
 */
interface AdherenceRepository {
    suspend fun logDose(
        medicationId: String,
        window: TimeWindow,
        takenAt: Instant = Instant.now(),
        dose: Double? = null,
    )

    suspend fun undoDose(
        medicationId: String,
        date: LocalDate,
        window: TimeWindow,
    )
}

data class CreateMedicationRequest(
    val drugId: String? = null,
    val customName: String? = null,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfig,
    val timeSlots: List<TimeSlot>,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val correlatedMarkers: List<String> = emptyList(),
    val startDate: LocalDate? = null,
)

data class UpdateMedicationRequest(
    val customName: String? = null,
    val dose: Double? = null,
    val unit: String? = null,
    val frequency: FrequencyConfig? = null,
    val timeSlots: List<TimeSlot>? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val correlatedMarkers: List<String>? = null,
    val changeNotes: String? = null,
)

/**
 * Change the dose effective on [startDate] (defaults to today server-side).
 * The backend closes the current open dosage period and opens a new one,
 * preserving the dose history.
 */
data class ChangeDoseRequest(
    val dose: Double,
    val unit: String? = null,
    val startDate: LocalDate? = null,
    val changeNotes: String? = null,
)
