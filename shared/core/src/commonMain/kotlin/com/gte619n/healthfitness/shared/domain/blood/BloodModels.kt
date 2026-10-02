package com.gte619n.healthfitness.shared.domain.blood

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/blood/BloodModels.kt (D3: Moshi→kotlinx.serialization).
 *
 * `java.time.Instant`/`LocalDate` were swapped for the kotlinx-datetime
 * equivalents; field names and nullability match the Android source 1:1.
 */

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
@Serializable
data class ReferenceRange(
    val unit: String,
    val orientation: Orientation,
    val goodThreshold: Double,
    val displayMin: Double,
    val displayMax: Double,
) {
    enum class Orientation { LOWER_IS_BETTER, HIGHER_IS_BETTER }
}

@Serializable
data class BloodReading(
    val readingId: String,
    val marker: BloodMarker,
    val value: Double,
    val unit: String,
    val sampleDate: LocalDate,
    val labSource: String?,
    val notes: String?,
    val reference: ReferenceRange,
    /**
     * IMPL-AND-20 (#40) — the mirror row's per-row sync state
     * (`SYNCED | PENDING | FAILED`) for the D11 [SyncBadge]. Null when the source
     * isn't the Room mirror (e.g. a live/kill-switch read). Defaulted so it never
     * breaks existing constructions.
     */
    val syncState: String? = null,
)

/**
 * A single marker row extracted from a lab PDF. [name] is the backend's
 * canonical name (e.g. "LDL"); it may or may not map to a [BloodMarker].
 */
@Serializable
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

@Serializable
data class BloodTestReport(
    val reportId: String,
    val sampleDate: LocalDate?,
    val labSource: String,
    val markers: List<ExtractedMarker>,
    /** Relative path, e.g. "/api/me/blood/reports/{id}/pdf". */
    val pdfDownloadPath: String,
    val createdAt: Instant,
)

@Serializable
data class MarkerHistoryPoint(
    val date: LocalDate,
    val value: Double,
    val source: Source,
) {
    @Serializable
    sealed interface Source {
        @Serializable
        data object Manual : Source

        @Serializable
        data class Lab(val reportId: String, val labSource: String) : Source
    }
}

/**
 * Combined latest-value view used by both the Blood overview "Tracked markers"
 * grid and the dashboard's BloodPanel. Derived in core-domain so both consumers
 * share one shape and ordering. See [LatestMarkers.derive].
 */
@Serializable
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
@Serializable
sealed interface UploadEvent {
    @Serializable
    data object Uploading : UploadEvent

    @Serializable
    data object Extracting : UploadEvent

    @Serializable
    data object Saving : UploadEvent

    @Serializable
    data class Complete(val report: BloodTestReport) : UploadEvent

    @Serializable
    data class Failed(val error: String) : UploadEvent
}
