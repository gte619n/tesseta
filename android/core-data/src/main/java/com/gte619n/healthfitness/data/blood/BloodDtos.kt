package com.gte619n.healthfitness.data.blood

import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import java.time.Instant
import java.time.LocalDate

// Plain Moshi-reflection DTOs (no @JsonClass codegen). Field names match the
// backend BloodReadingResponse / BloodTestReportResponse JSON shapes.

internal data class BloodReadingDto(
    val readingId: String,
    val marker: String,
    val value: Double,
    val unit: String,
    val sampleDate: String, // ISO yyyy-MM-dd
    val labSource: String?,
    val notes: String?,
    val reference: ReferenceDto,
)

internal data class ReferenceDto(
    val unit: String,
    val orientation: String, // "LOWER_IS_BETTER" | "HIGHER_IS_BETTER"
    val goodThreshold: Double,
    val displayMin: Double,
    val displayMax: Double,
)

internal data class CreateReadingRequestDto(
    val marker: String,
    val value: Double,
    val unit: String?,
    val sampleDate: String,
    val labSource: String?,
    val notes: String?,
)

internal data class BloodTestReportDto(
    val reportId: String,
    val sampleDate: String?,
    val labSource: String,
    val markers: List<ExtractedMarkerDto>,
    val pdfDownloadPath: String?,
    val createdAt: String?,
)

internal data class ExtractedMarkerDto(
    val name: String,
    val value: Double?,
    val unit: String?,
    val refRangeLow: Double?,
    val refRangeHigh: Double?,
    val flag: String?, // "H" | "L" | null
)

// --- Mappers ---

internal fun ReferenceDto.toDomain(): ReferenceRange = ReferenceRange(
    unit = unit,
    orientation = when (orientation.uppercase()) {
        "HIGHER_IS_BETTER" -> ReferenceRange.Orientation.HIGHER_IS_BETTER
        else -> ReferenceRange.Orientation.LOWER_IS_BETTER
    },
    goodThreshold = goodThreshold,
    displayMin = displayMin,
    displayMax = displayMax,
)

internal fun BloodReadingDto.toDomain(): BloodReading = BloodReading(
    readingId = readingId,
    marker = BloodMarker.valueOf(marker),
    value = value,
    unit = unit,
    sampleDate = LocalDate.parse(sampleDate),
    labSource = labSource,
    notes = notes,
    reference = reference.toDomain(),
)

internal fun ExtractedMarkerDto.toDomain(): ExtractedMarker = ExtractedMarker(
    name = name,
    value = value,
    unit = unit,
    refRangeLow = refRangeLow,
    refRangeHigh = refRangeHigh,
    flag = when (flag?.uppercase()) {
        "H" -> ExtractedMarker.Flag.H
        "L" -> ExtractedMarker.Flag.L
        else -> null
    },
)

internal fun BloodTestReportDto.toDomain(): BloodTestReport = BloodTestReport(
    reportId = reportId,
    sampleDate = sampleDate?.let { LocalDate.parse(it) },
    labSource = labSource,
    markers = markers.map { it.toDomain() },
    pdfDownloadPath = pdfDownloadPath ?: "/api/me/blood/reports/$reportId/pdf",
    createdAt = createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
)
