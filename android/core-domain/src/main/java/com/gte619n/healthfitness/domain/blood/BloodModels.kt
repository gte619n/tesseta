package com.gte619n.healthfitness.domain.blood

import java.time.Instant
import java.time.LocalDate

/**
 * Tracked blood markers. Mirrors the backend `BloodMarker` enum exactly so that
 * enum names round-trip across the wire without translation.
 */
enum class BloodMarker {
    TOTAL_CHOLESTEROL,
    LDL,
    HDL,
    TRIGLYCERIDES,
    APO_B,
    HBA1C,
    FASTING_GLUCOSE,
    HS_CRP,
    TESTOSTERONE,
}

/**
 * Server-authoritative reference range for a marker reading. Android never
 * guesses these — they ride along on every [BloodReading].
 */
data class ReferenceRange(
    val unit: String,
    val orientation: Orientation,
    val goodThreshold: Double,
    val displayMin: Double,
    val displayMax: Double,
) {
    enum class Orientation { LOWER_IS_BETTER, HIGHER_IS_BETTER }
}

data class BloodReading(
    val readingId: String,
    val marker: BloodMarker,
    val value: Double,
    val unit: String,
    val sampleDate: LocalDate,
    val labSource: String?,
    val notes: String?,
    val reference: ReferenceRange,
)

/**
 * A single marker row extracted from a lab PDF. [name] is the backend's
 * canonical name (e.g. "LDL"); it may or may not map to a [BloodMarker].
 */
data class ExtractedMarker(
    val name: String,
    val value: Double?,
    val unit: String?,
    val refRangeLow: Double?,
    val refRangeHigh: Double?,
    val flag: Flag?,
) {
    enum class Flag { H, L }
}

data class BloodTestReport(
    val reportId: String,
    val sampleDate: LocalDate?,
    val labSource: String,
    val markers: List<ExtractedMarker>,
    /** Relative path, e.g. "/api/me/blood/reports/{id}/pdf". */
    val pdfDownloadPath: String,
    val createdAt: Instant,
)

data class MarkerHistoryPoint(
    val date: LocalDate,
    val value: Double,
    val source: Source,
) {
    sealed interface Source {
        data object Manual : Source
        data class Lab(val reportId: String, val labSource: String) : Source
    }
}

/**
 * Combined latest-value view used by both the Blood overview "Tracked markers"
 * grid and the dashboard's BloodPanel. Derived in core-domain so both consumers
 * share one shape and ordering. See [LatestMarkers.derive].
 */
data class LatestMarker(
    val marker: BloodMarker,
    val value: Double?,
    val unit: String,
    val sampleDate: LocalDate?,
    val reference: ReferenceRange?,
    val flag: ExtractedMarker.Flag?,
    /** Up to the last 12 months of points, oldest → newest. */
    val history: List<MarkerHistoryPoint>,
    val source: Source,
) {
    enum class Source { MANUAL, LAB, NONE }
}

/** Phases streamed back from the lab-PDF upload endpoint. */
sealed interface UploadEvent {
    data object Uploading : UploadEvent
    data object Extracting : UploadEvent
    data object Saving : UploadEvent
    data class Complete(val report: BloodTestReport) : UploadEvent
    data class Failed(val error: String) : UploadEvent
}
