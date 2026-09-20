package com.gte619n.healthfitness.data.workouts.adhoc

import com.gte619n.healthfitness.domain.workouts.adhoc.AdHocLibraryItem

/**
 * Wire mirror of the persisted {@code adhocWorkouts} document (IMPL-ADHOC-01),
 * decoded from the sync delta's {@code payloadJson}. Only the fields the Library
 * list needs are declared; Moshi ignores the rest (the full {@code day} tree,
 * prompt, timestamps). Every field is nullable so a partial/legacy doc degrades
 * to a sane row rather than failing the whole parse. {@code adhocId} is injected
 * into the payload by the sync engine (CollectionRegistry idField), so it is
 * present on mirrored rows.
 */
data class AdHocWorkoutMirrorDto(
    val adhocId: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val tags: List<String>? = null,
    val pinned: Boolean? = null,
    val estimatedDurationSeconds: Int? = null,
    val runCount: Int? = null,
    val equipmentContext: EquipmentContextDto? = null,
) {
    data class EquipmentContextDto(val label: String? = null)

    fun toDomain(): AdHocLibraryItem? {
        val id = adhocId ?: return null
        return AdHocLibraryItem(
            adhocId = id,
            title = title ?: "Workout",
            summary = summary,
            tags = tags ?: emptyList(),
            pinned = pinned ?: false,
            estimatedDurationSeconds = estimatedDurationSeconds,
            runCount = runCount ?: 0,
            equipmentLabel = equipmentContext?.label,
        )
    }
}
