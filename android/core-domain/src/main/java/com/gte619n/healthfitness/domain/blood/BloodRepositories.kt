package com.gte619n.healthfitness.domain.blood

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Repository contracts for blood data. Both the dashboard and the new
 * `feature-blood` module consume these. Implementations live in
 * `core-data/.../data/blood/`.
 */

interface BloodReadingRepository {
    /** Hot stream of all manual readings. Backed by an in-memory cache. */
    fun observeReadings(): Flow<List<BloodReading>>

    /** Refetch from the backend. Errors propagate to the caller. */
    suspend fun refresh()

    /**
     * POSTs a new reading. `unit` may be null — the backend falls back to
     * the marker's canonical unit when omitted.
     */
    suspend fun create(
        marker: BloodMarker,
        value: Double,
        unit: String?,
        sampleDate: LocalDate,
        labSource: String?,
        notes: String?,
    ): BloodReading

    suspend fun delete(readingId: String)
}

interface BloodTestReportRepository {
    /** Hot stream of all uploaded lab reports. */
    fun observeReports(): Flow<List<BloodTestReport>>

    suspend fun refresh()

    /** Fetches a single report by id (full extracted-markers payload). */
    suspend fun get(reportId: String): BloodTestReport

    suspend fun delete(reportId: String)

    /**
     * Downloads the raw PDF bytes for a report. Used by the report-detail
     * screen to cache the PDF locally before launching ACTION_VIEW.
     */
    suspend fun downloadPdf(reportId: String): ByteArray

    /**
     * Uploads a PDF and streams extraction phases. Terminal events are
     * [UploadEvent.Complete] or [UploadEvent.Failed]. Cold flow — the
     * upload starts on collect; cancelling aborts the request.
     */
    fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent>
}

sealed interface UploadEvent {
    data object Uploading : UploadEvent
    data object Extracting : UploadEvent
    data object Saving : UploadEvent
    data class Complete(val report: BloodTestReport) : UploadEvent
    data class Failed(val error: String) : UploadEvent
}
