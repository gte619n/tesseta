package com.gte619n.healthfitness.shared.domain.medications

import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave B — ported from
 * android/core-domain/.../domain/medications/MedicationRequests.kt.
 * `java.time.LocalDate` → kotlinx-datetime. Field names / nullability match the
 * Android source 1:1 (the core-data layer maps these to wire DTOs).
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
 * [PR#8] Effective-dated dose change. `unit` defaults to the med's current unit;
 * `startDate` defaults to today server-side.
 */
data class ChangeDoseRequest(
    val dose: Double,
    val unit: String? = null,
    val startDate: LocalDate? = null,
    val changeNotes: String? = null,
)

/**
 * IMPL-IOS-01 Phase 3 Wave B — ported from the Android inline-reminder UI state
 * (`feature-medical/.../reminders/InlineReminderControls.kt`). The editable
 * per-medication reminder override captured on the add/edit forms: a mute toggle
 * plus optional per-window "Remind at…" custom times ("HH:mm"). An all-default
 * config (`enabled && times.isEmpty()`) means "use the global window defaults" and
 * clears any stored override so the settings doc stays minimal.
 */
data class InlineReminderConfig(
    val enabled: Boolean = true,
    val times: Map<TimeWindow, String> = emptyMap(),
)

/**
 * IMPL-IOS-01 Phase 3 Wave B — ported from
 * android/core-domain/.../domain/medications/DrugLookupEvent.kt. The AI drug
 * lookup SSE stream events for the Add flow's search step.
 */
sealed interface DrugLookupEvent {
    data class Progress(val phase: String, val message: String?) : DrugLookupEvent
    data class Found(val drug: Drug) : DrugLookupEvent
    data class NotFound(val message: String?) : DrugLookupEvent
    data class Failed(val error: String) : DrugLookupEvent
}
