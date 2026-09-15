package com.gte619n.healthfitness.data.workouts

// IMPL-GYM-003: wire DTOs for gym-video equipment detection. Plain Moshi data
// classes (reflection via workoutsMoshi), mirroring the backend contract. The
// preview/confirm shapes match the bulk-import ones so the same review UI applies.

data class ScanRegisterRequestDto(
    val mimeType: String,
    val sizeBytes: Long,
)

data class ScanRegisterResponseDto(
    val scanId: String,
    val uploadUrl: String,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap(),
    val status: String = "REGISTERED",
)

data class ScanStatusResponseDto(
    val scanId: String,
    val status: String,
    val detectedCount: Int? = null,
    val preview: ImportPreviewResponseDto? = null,
    val error: String? = null,
)

data class ParsedEquipmentDto(
    val name: String,
    val brand: String? = null,
    val category: String = "",
    val subcategory: String = "",
    val specSchema: String? = null,
    val specs: Map<String, Any?> = emptyMap(),
    val confidence: String? = null,
    val rawText: String? = null,
)

data class ImportMatchDto(
    val equipmentId: String,
    val name: String,
    val score: Double,
    val reason: String = "",
)

data class ImportPreviewItemDto(
    val index: Int,
    val parsed: ParsedEquipmentDto,
    val match: ImportMatchDto? = null,
    val action: String,
)

data class ImportPreviewSummaryDto(
    val total: Int = 0,
    val matched: Int = 0,
    val suggestedMatches: Int = 0,
    val newSubmissions: Int = 0,
)

data class ImportPreviewResponseDto(
    val items: List<ImportPreviewItemDto> = emptyList(),
    val summary: ImportPreviewSummaryDto = ImportPreviewSummaryDto(),
)

data class ImportConfirmItemDto(
    val index: Int,
    val action: String,
    val matchedEquipmentId: String? = null,
    val parsed: ParsedEquipmentDto? = null,
    val overrides: NameOverrideDto? = null,
)

data class NameOverrideDto(val name: String)

data class ImportConfirmRequestDto(
    val items: List<ImportConfirmItemDto>,
)

data class ImportConfirmResponseDto(
    val created: List<CreatedEquipmentDto> = emptyList(),
    val matched: List<MatchedEquipmentDto> = emptyList(),
    val addedToLocation: Int = 0,
    val skipped: Int = 0,
    val failed: List<FailedImportItemDto> = emptyList(),
)

data class CreatedEquipmentDto(val equipmentId: String, val name: String, val status: String)
data class MatchedEquipmentDto(val equipmentId: String, val name: String)
data class FailedImportItemDto(val index: Int, val name: String, val reason: String)
