package com.gte619n.healthfitness.shared.domain.medications

import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/medications/MedicationModels.kt
 * (D3: Moshi→kotlinx.serialization).
 *
 * `java.time.Instant`/`LocalDate`/`LocalTime` swapped for kotlinx-datetime; the
 * shared [DayOfWeek] moved with it. Field names and nullability match the
 * Android source 1:1.
 *
 * Medications domain models (IMPL-AND-03). Pure Kotlin — no framework deps.
 * Field names mirror the backend `MedicationResponse` to keep the wire mapping
 * trivial.
 *
 * Day-of-week uses the shared [DayOfWeek] (`domain.common`). NOTE: for
 * medication `specificDays` the backend EMITS uppercase enum values
 * (`["MON","THU"]`) and accepts any case on read — kotlinx.serialization's
 * default enum codec matches the emitted form. See [DayOfWeek] for the full
 * (non-uniform) wire-case contract.
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

@Serializable
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

@Serializable
data class FrequencyConfig(
    val type: FrequencyType,
    val timesPerPeriod: Int? = null,
    val specificDays: List<DayOfWeek>? = null,
    val cycle: CycleConfig? = null,
) {
    @Serializable
    data class CycleConfig(
        val onWeeks: Int,
        val offWeeks: Int,
        val startDate: LocalDate,
    )
}

@Serializable
data class TimeSlot(
    val window: TimeWindow,
    val dose: Double,
    /**
     * IMPL-21: optional explicit reminder time for this slot, set in drug setup.
     * When non-null it is the highest-precedence reminder time for the slot
     * (drug-setup explicit → per-med settings override → global window default);
     * when null the window's configured time applies. Windows remain the backbone
     * for grouping and adherence keying.
     */
    val time: LocalTime? = null,
)

/**
 * [PR#8] Dated dose history. The active/current period has `endDate == null`.
 * End dates are exclusive (a closed period's end == the next period's start).
 */
@Serializable
data class DosagePeriod(
    val dose: Double,
    val unit: String,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
) {
    val isActive: Boolean get() = endDate == null
}

@Serializable
data class AdherenceSummary(
    val last30Days: List<DayAdherence>,
    val percentage: Double,
) {
    @Serializable
    data class DayAdherence(val date: LocalDate, val taken: Boolean)
}

@Serializable
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
    /**
     * IMPL-AND-20 (#40) — the mirror row's per-row sync state
     * (`SYNCED | PENDING | FAILED`) for the D11 SyncBadge. Null for a live read.
     * Defaulted so existing constructions are unaffected.
     */
    val syncState: String? = null,
) {
    val displayName: String
        get() = customName ?: drug?.name ?: "Unknown"
}

@Serializable
data class MedicationHistoryEntry(
    val historyId: String,
    val changeType: ChangeType,
    val previousValue: String,
    val newValue: String,
    val changedAt: Instant,
    val notes: String?,
)

@Serializable
data class MedicationDetail(
    val medication: Medication,
    val history: List<MedicationHistoryEntry>,
)

@Serializable
data class TodaysDose(
    val medicationId: String,
    val drugName: String,
    val window: TimeWindow,
    val dose: Double,
    val unit: String,
    val taken: Boolean,
    val takenAt: Instant?,
)
