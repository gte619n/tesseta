package com.gte619n.healthfitness.domain.blood

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface BloodReadingRepository {
    fun observeReadings(): Flow<List<BloodReading>>
    suspend fun refresh()
    suspend fun create(
        marker: BloodMarker,
        value: Double,
        /** null → server default unit for the marker. */
        unit: String?,
        sampleDate: LocalDate,
        labSource: String?,
        notes: String?,
    ): BloodReading

    suspend fun delete(readingId: String)
}

interface BloodTestReportRepository {
    fun observeReports(): Flow<List<BloodTestReport>>
    suspend fun refresh()
    suspend fun get(reportId: String): BloodTestReport
    suspend fun delete(reportId: String)

    /** Downloads the report's PDF bytes. [pdfDownloadPath] is relative, e.g. "/api/me/blood/reports/{id}/pdf". */
    suspend fun downloadPdf(pdfDownloadPath: String): ByteArray

    /**
     * Uploads a PDF and streams extraction phases. Terminal events are
     * [UploadEvent.Complete] or [UploadEvent.Failed]. Cold flow: collection
     * starts the upload; cancelling the collector aborts the request.
     */
    fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent>
}
