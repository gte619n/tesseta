package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Wire DTOs for the medications domain (IMPL-AND-03). Plain Kotlin data
 * classes — Moshi uses reflection (KotlinJsonAdapterFactory registered
 * globally in NetworkModule), so no `@JsonClass(generateAdapter = true)`
 * codegen is needed. `@Json(name = ...)` may be used for field renames, but
 * the field names here already match the backend wire shape.
 *
 * `LocalDate` / `Instant` / `DayOfWeek` are handled by the globally
 * registered adapters (LocalDateAdapter, InstantAdapter, DayOfWeekMoshiAdapter).
 */

data class DrugDto(
    val drugId: String,
    val name: String,
    val aliases: List<String>? = null,
    val category: String,
    val form: String,
    val defaultUnit: String,
    val commonDoses: List<String>? = null,
    val imageUrl: String? = null,
    val imageFallback: String? = null,
    val suggestedMarkers: List<String>? = null,
    val description: String? = null,
)

data class CycleConfigDto(
    val onWeeks: Int,
    val offWeeks: Int,
    val startDate: LocalDate,
)

data class FrequencyConfigDto(
    val type: String,
    val timesPerPeriod: Int? = null,
    val specificDays: List<DayOfWeek>? = null,
    val cycle: CycleConfigDto? = null,
)

data class TimeSlotDto(
    val window: String,
    val dose: Double,
)

/** [PR#8] Dated dose history entry. */
data class DosagePeriodDto(
    val dose: Double,
    val unit: String,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
)

data class DayAdherenceDto(
    val date: LocalDate,
    val taken: Boolean,
)

data class AdherenceSummaryDto(
    val last30Days: List<DayAdherenceDto>? = null,
    val percentage: Double = 0.0,
)

data class MedicationDto(
    val medicationId: String,
    val drugId: String? = null,
    val drug: DrugDto? = null,
    val customName: String? = null,
    val status: String,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfigDto,
    val timeSlots: List<TimeSlotDto>? = null,
    val protocolId: String? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val discontinueReason: String? = null,
    val discontinueNotes: String? = null,
    val correlatedMarkers: List<String>? = null,
    val dosagePeriods: List<DosagePeriodDto>? = null, // [PR#8]
    val adherence: AdherenceSummaryDto? = null,
)

data class MedicationHistoryEntryDto(
    val historyId: String,
    val changeType: String,
    val previousValue: String? = null,
    val newValue: String? = null,
    val changedAt: Instant,
    val notes: String? = null,
)

/**
 * Detail response for a single medication. The backend `MedicationDetailResponse`
 * is **flat** — the medication's fields sit at the top level alongside `history`
 * (it mirrors `MedicationResponse` and appends `history`), exactly like web's
 * `MedicationDetail extends Medication`. It is NOT `{ medication: {...}, history }`.
 * Mapping the wrong (nested) shape made Moshi fail on the required `medication`
 * field, so opening a medication's detail/history never loaded.
 */
data class MedicationDetailDto(
    val medicationId: String,
    val drugId: String? = null,
    val drug: DrugDto? = null,
    val customName: String? = null,
    val status: String,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfigDto,
    val timeSlots: List<TimeSlotDto>? = null,
    val protocolId: String? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val discontinueReason: String? = null,
    val discontinueNotes: String? = null,
    val correlatedMarkers: List<String>? = null,
    val dosagePeriods: List<DosagePeriodDto>? = null,
    val history: List<MedicationHistoryEntryDto>? = null,
)

data class TodaysDoseDto(
    val medicationId: String,
    val drugName: String,
    val window: String,
    val dose: Double,
    val unit: String,
    val taken: Boolean = false,
    val takenAt: Instant? = null,
)

// ---- write-path DTOs ----

data class CreateMedicationDto(
    val drugId: String? = null,
    val customName: String? = null,
    val customCategory: String? = null,
    val customForm: String? = null,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfigDto,
    val timeSlots: List<TimeSlotDto>,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val correlatedMarkers: List<String> = emptyList(),
)

data class UpdateMedicationDto(
    val customName: String? = null,
    val dose: Double? = null,
    val unit: String? = null,
    val frequency: FrequencyConfigDto? = null,
    val timeSlots: List<TimeSlotDto>? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val correlatedMarkers: List<String>? = null,
    val startDate: LocalDate? = null,              // [PR#8]
    val dosagePeriods: List<DosagePeriodDto>? = null, // [PR#8]
    val changeNotes: String? = null,
)

/** [PR#8] change dose effective on a date. */
data class ChangeDoseDto(
    val dose: Double,
    val unit: String? = null,
    val startDate: LocalDate? = null,
    val changeNotes: String? = null,
)

/** [PR#8] resume a discontinued medication. */
data class ReactivateDto(
    val resumeDate: LocalDate? = null,
)

data class DiscontinueDto(
    val reason: String,
    val notes: String? = null,
    val endDate: LocalDate? = null, // [PR#8] explicit end date closes the open period
)

data class LogDoseDto(
    val window: String,
    val takenAt: Instant,
    val dose: Double? = null,
)
