package com.gte619n.healthfitness.domain.blood

import java.time.Instant
import java.time.LocalDate

/**
 * Pure-Kotlin domain models for the blood feature. Mirrors the backend's
 * `BloodMarker`, `BloodReading`, `BloodTestReport`, and `ExtractedMarker`
 * but stripped of wire-only concerns (no string-typed enums, no
 * reference shape that lives inside a "reading" wrapper).
 */

/**
 * Canonical blood markers known to the backend. Storage uses the enum
 * name verbatim — the backend's `BloodMarker` enum is the source of
 * truth (see Stage 01 note re: TESTOSTERONE not being present).
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
}

/**
 * Reference range info as published by the backend on each reading. The
 * goodThreshold is the boundary of the "good zone"; orientation tells
 * the chart whether values below or above the threshold are desirable.
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

/**
 * A single manual or lab-extracted blood reading the user has logged.
 * `notes` and `labSource` are optional free-text.
 */
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
 * A row extracted from an uploaded lab PDF. `name` is canonical (e.g.
 * "LDL"), but the backend may emit values that don't map to any known
 * [BloodMarker]; the UI renders unknown names under "Other markers"
 * rather than discarding the row.
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

/**
 * One uploaded lab report. `pdfDownloadPath` is the backend route to
 * stream the original PDF (e.g. `/api/me/blood/reports/{id}/pdf`); the
 * Android viewer hits that path with the existing OkHttp + auth client.
 */
data class BloodTestReport(
    val reportId: String,
    val sampleDate: LocalDate?,
    val labSource: String,
    val markers: List<ExtractedMarker>,
    val pdfDownloadPath: String,
    val createdAt: Instant?,
)

/**
 * One point on a marker's history chart. The source distinguishes
 * manual entries from lab-extracted ones — the UI renders a small badge
 * (e.g. "Manual" vs. "Lab - Quest Diagnostics") in the reading table.
 */
data class MarkerHistoryPoint(
    val date: LocalDate,
    val value: Double,
    val unit: String,
    val source: Source,
) {
    sealed interface Source {
        data object Manual : Source
        data class Lab(val reportId: String, val labSource: String) : Source
    }
}

/**
 * Combined view used by both the Blood overview "Tracked markers" grid
 * and the dashboard `BloodPanel`. Derived from the union of
 * [BloodReading]s and [BloodTestReport]s by [LatestMarkers.derive].
 */
data class LatestMarker(
    val marker: BloodMarker,
    val value: Double?,
    val unit: String,
    val sampleDate: LocalDate?,
    val reference: ReferenceRange?,
    val flag: ExtractedMarker.Flag?,
    /** Up to last 12 months of points, ascending by date, deduped per day. */
    val history: List<MarkerHistoryPoint>,
    val source: Source,
) {
    enum class Source { MANUAL, LAB, NONE }
}
