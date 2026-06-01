package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Medications domain models (IMPL-AND-03). Pure Kotlin — no framework deps.
 * Field names mirror the backend `MedicationResponse` to keep wire/Moshi
 * mapping trivial.
 *
 * Day-of-week uses the shared [DayOfWeek] (`domain.common`); the backend wire
 * form is lowercase (`"mon"`…`"sun"`) and the core-data Moshi adapter handles
 * the case mapping.
 */

enum class DrugCategory { PRESCRIPTION, SUPPLEMENT, OTC, PEPTIDE, TOPICAL }

enum class DrugForm {
    INJECTABLE_VIAL, TABLET, CAPSULE, SOFTGEL,
    CREAM, PATCH, LIQUID, POWDER,
}

enum class MedicationStatus { ACTIVE, DISCONTINUED }

enum class FrequencyType { DAILY, WEEKLY, MONTHLY, PRN, CYCLE }

enum class TimeWindow { MORNING, AFTERNOON, EVENING, BEDTIME }

enum class DiscontinueReason { COMPLETED, SIDE_EFFECTS, SWITCHED, COST, OTHER }

enum class ChangeType { DOSE_CHANGE, FREQUENCY_CHANGE, SCHEDULE_CHANGE }

data class Drug(
    val drugId: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val category: DrugCategory,
    val form: DrugForm,
    val defaultUnit: String,
    val commonDoses: List<String> = emptyList(),
    val imageUrl: String?,
    val imageFallback: String?,
    val suggestedMarkers: List<String> = emptyList(),
    val description: String? = null,
)

data class FrequencyConfig(
    val type: FrequencyType,
    val timesPerPeriod: Int? = null,
    val specificDays: List<DayOfWeek>? = null,
    val cycle: CycleConfig? = null,
) {
    data class CycleConfig(
        val onWeeks: Int,
        val offWeeks: Int,
        val startDate: LocalDate,
    )
}

data class TimeSlot(
    val window: TimeWindow,
    val dose: Double,
)

/**
 * [PR#8] Dated dose history. The active/current period has `endDate == null`.
 * End dates are exclusive (a closed period's end == the next period's start).
 */
data class DosagePeriod(
    val dose: Double,
    val unit: String,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
) {
    val isActive: Boolean get() = endDate == null
}

data class AdherenceSummary(
    val last30Days: List<DayAdherence>,
    val percentage: Double,
) {
    data class DayAdherence(val date: LocalDate, val taken: Boolean)
}

data class Medication(
    val medicationId: String,
    val drugId: String?,
    val drug: Drug?,
    val customName: String?,
    val status: MedicationStatus,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfig,
    val timeSlots: List<TimeSlot>,
    val protocolId: String?,
    val notes: String?,
    val prescribedBy: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val discontinueReason: DiscontinueReason?,
    val discontinueNotes: String?,
    val correlatedMarkers: List<String>,
    val dosagePeriods: List<DosagePeriod> = emptyList(), // [PR#8] dated dose history
    val adherence: AdherenceSummary?,
) {
    val displayName: String
        get() = customName ?: drug?.name ?: "Unknown"
}

data class MedicationHistoryEntry(
    val historyId: String,
    val changeType: ChangeType,
    val previousValue: String,
    val newValue: String,
    val changedAt: Instant,
    val notes: String?,
)

data class MedicationDetail(
    val medication: Medication,
    val history: List<MedicationHistoryEntry>,
)

data class TodaysDose(
    val medicationId: String,
    val drugName: String,
    val window: TimeWindow,
    val dose: Double,
    val unit: String,
    val taken: Boolean,
    val takenAt: Instant?,
)
