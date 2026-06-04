package com.gte619n.healthfitness.domain.workouts

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.io.InputStream

/**
 * A file staged for upload. [source] is invoked lazily to obtain a fresh
 * stream (so the upload layer can read it on a background dispatcher).
 */
data class PendingUpload(
    val filename: String,
    val mimeType: String,
    val source: () -> InputStream,
)

data class CreateLocationRequest(
    val name: String,
    val address: String?,
    val is24Hours: Boolean,
    val hours: Map<DayOfWeek, HoursSlot>?,
    val amenities: List<String>,
    val equipmentIds: List<String> = emptyList(),
)

data class UpdateLocationRequest(
    val name: String? = null,
    val address: String? = null,
    val is24Hours: Boolean? = null,
    val hours: Map<DayOfWeek, HoursSlot>? = null,
    val amenities: List<String>? = null,
    val equipmentIds: List<String>? = null,
)

data class CreateEquipmentRequest(
    val name: String,
    val category: String,
    val subcategory: String,
    val specSchema: SpecSchemaTag,
    val specs: EquipmentSpec,
)
