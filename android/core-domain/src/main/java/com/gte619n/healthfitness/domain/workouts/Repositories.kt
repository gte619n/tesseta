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

interface LocationRepository {
    suspend fun list(includeInactive: Boolean = false): Result<List<Location>>
    suspend fun get(locationId: String): Result<Location>
    suspend fun create(req: CreateLocationRequest): Result<Location>
    suspend fun update(locationId: String, req: UpdateLocationRequest): Result<Location>
    suspend fun delete(locationId: String): Result<Unit>
    suspend fun setDefault(locationId: String): Result<Unit>
    suspend fun uploadCoverPhoto(locationId: String, file: PendingUpload): Result<String>
    suspend fun deleteCoverPhoto(locationId: String): Result<Unit>
    suspend fun addEquipment(locationId: String, equipmentId: String): Result<Unit>
    suspend fun removeEquipment(locationId: String, equipmentId: String): Result<Unit>
    suspend fun updateEquipmentSpecs(
        locationId: String,
        equipmentId: String,
        specs: Map<String, Any?>,
    ): Result<Location>
}

interface EquipmentRepository {
    suspend fun searchCatalog(
        search: String? = null,
        category: String? = null,
        subcategory: String? = null,
    ): Result<List<Equipment>>

    suspend fun get(equipmentId: String): Result<Equipment>
    suspend fun categories(): Result<Map<String, List<String>>>
    suspend fun submit(req: CreateEquipmentRequest): Result<Equipment>
    suspend fun mySubmissions(): Result<List<Equipment>>
    suspend fun deleteSubmission(equipmentId: String): Result<Unit>
}
