package com.gte619n.healthfitness.data.medications

import java.time.Instant
import java.time.LocalDate

/**
 * Wire-shaped DTOs for the medications + drugs + adherence endpoints.
 * Internal — mappers in [MedicationMapper] convert them to domain models
 * before anything north of the repository sees them.
 *
 * Verified backend response shapes:
 *  - GET /api/me/medications →
 *    [{ medicationId, drugId, drug, customName, status, dose, unit,
 *       frequency, timeSlots, protocolId, notes, prescribedBy, startDate,
 *       endDate, discontinueReason, discontinueNotes, correlatedMarkers,
 *       adherence }]
 *  - GET /api/me/medications/{id} →
 *    MedicationDetailResponse: same fields + `history: HistoryEntry[]`
 *  - POST /api/me/medications (CreateMedicationRequest) →
 *    MedicationResponse
 *  - PUT  /api/me/medications/{id} (UpdateMedicationRequest) →
 *    MedicationResponse
 *  - POST /api/me/medications/{id}/discontinue (DiscontinueRequest) →
 *    MedicationResponse
 *  - DELETE /api/me/medications/{id} → 204
 *  - GET /api/me/medications/today →
 *    [{ medicationId, drugName, imageUrl, window, dose, unit, taken, takenAt }]
 *  - POST /api/me/medications/{id}/adherence (LogDoseRequest) → 201
 *  - DELETE /api/me/medications/{id}/adherence/{date}/{window} → 204
 *  - GET /api/drugs(?q=) → [DrugResponse]
 *  - GET /api/drugs/{id} → DrugResponse
 *  - POST /api/drugs/lookup/stream (LookupRequest, SSE) → phase events
 *
 * Field names match the backend `Response` records 1:1 — the reflective
 * Moshi adapter (registered in `core-network` NetworkModule) handles
 * (de)serialisation without `@Json(name=...)` overrides.
 */

internal data class DrugDto(
    val drugId: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val category: String,
    val form: String,
    val defaultUnit: String,
    val commonDoses: List<String> = emptyList(),
    val imageUrl: String?,
    val imageFallback: String?,
    val suggestedMarkers: List<String> = emptyList(),
    val description: String? = null,
)

internal data class CycleConfigDto(
    val onWeeks: Int,
    val offWeeks: Int,
    val startDate: LocalDate,
)

internal data class FrequencyConfigDto(
    val type: String,
    val timesPerPeriod: Int? = null,
    val specificDays: List<String>? = null,
    val cycle: CycleConfigDto? = null,
)

internal data class TimeSlotDto(
    val window: String,
    val dose: Double,
)

internal data class DosagePeriodDto(
    val dose: Double,
    val unit: String,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
)

internal data class DayAdherenceDto(
    val date: LocalDate,
    val taken: Boolean,
)

internal data class AdherenceSummaryDto(
    val last30Days: List<DayAdherenceDto> = emptyList(),
    val percentage: Double = 0.0,
)

internal data class MedicationDto(
    val medicationId: String,
    val drugId: String?,
    val drug: DrugDto?,
    val customName: String?,
    val status: String,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfigDto,
    val timeSlots: List<TimeSlotDto> = emptyList(),
    val protocolId: String? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val discontinueReason: String? = null,
    val discontinueNotes: String? = null,
    val correlatedMarkers: List<String> = emptyList(),
    val dosagePeriods: List<DosagePeriodDto> = emptyList(),
    val adherence: AdherenceSummaryDto? = null,
)

internal data class HistoryEntryDto(
    val historyId: String,
    val changeType: String,
    val previousValue: String,
    val newValue: String,
    val changedAt: Instant,
    val notes: String? = null,
)

internal data class MedicationDetailDto(
    val medicationId: String,
    val drugId: String?,
    val drug: DrugDto?,
    val customName: String?,
    val status: String,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfigDto,
    val timeSlots: List<TimeSlotDto> = emptyList(),
    val protocolId: String? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val discontinueReason: String? = null,
    val discontinueNotes: String? = null,
    val correlatedMarkers: List<String> = emptyList(),
    val dosagePeriods: List<DosagePeriodDto> = emptyList(),
    val history: List<HistoryEntryDto> = emptyList(),
)

internal data class TodaysDoseDto(
    val medicationId: String,
    val drugName: String,
    val imageUrl: String? = null,
    val window: String,
    val dose: Double,
    val unit: String?,
    val taken: Boolean,
    val takenAt: Instant? = null,
)

// Request bodies

internal data class CreateMedicationDto(
    val drugId: String? = null,
    val customName: String? = null,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfigDto,
    val timeSlots: List<TimeSlotDto>? = null,
    val protocolId: String? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val startDate: LocalDate? = null,
    val correlatedMarkers: List<String>? = null,
)

internal data class UpdateMedicationDto(
    val customName: String? = null,
    val dose: Double? = null,
    val unit: String? = null,
    val frequency: FrequencyConfigDto? = null,
    val timeSlots: List<TimeSlotDto>? = null,
    val protocolId: String? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val correlatedMarkers: List<String>? = null,
    val changeNotes: String? = null,
)

internal data class ChangeDoseDto(
    val dose: Double,
    val unit: String? = null,
    val startDate: LocalDate? = null,
    val changeNotes: String? = null,
)

internal data class DiscontinueDto(
    val reason: String,
    val notes: String? = null,
    val endDate: LocalDate? = null,
)

internal data class LogDoseDto(
    val date: LocalDate? = null,
    val window: String,
    val dose: Double? = null,
    val notes: String? = null,
)

// SSE drug-lookup events

internal data class LookupPhaseDto(
    val phase: String,
    val message: String? = null,
    val error: String? = null,
    val drug: DrugDto? = null,
)

internal data class LookupRequestDto(
    val query: String,
)
