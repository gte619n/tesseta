package com.gte619n.healthfitness.domain.medications

import java.time.LocalDate

/**
 * Write-path request models for medications (IMPL-AND-03). These are pure
 * domain types; the core-data layer maps them to wire DTOs.
 */

data class CreateMedicationRequest(
    val drugId: String? = null,
    val customName: String? = null,
    val customCategory: DrugCategory? = null,
    val customForm: DrugForm? = null,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfig,
    val timeSlots: List<TimeSlot>,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val correlatedMarkers: List<String> = emptyList(),
)

data class UpdateMedicationRequest(
    val customName: String? = null,
    val dose: Double? = null,         // legacy; prefer changeDose() for dated history
    val unit: String? = null,
    val frequency: FrequencyConfig? = null,
    val timeSlots: List<TimeSlot>? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val correlatedMarkers: List<String>? = null,
    val startDate: LocalDate? = null,              // [PR#8] edit the medication start date
    val dosagePeriods: List<DosagePeriod>? = null, // [PR#8] full-replacement history correction (V1 unused)
    val changeNotes: String? = null,
)

/**
 * [PR#8] Effective-dated dose change. `unit` defaults to the med's current
 * unit; `startDate` defaults to today server-side.
 */
data class ChangeDoseRequest(
    val dose: Double,
    val unit: String? = null,
    val startDate: LocalDate? = null,
    val changeNotes: String? = null,
)
