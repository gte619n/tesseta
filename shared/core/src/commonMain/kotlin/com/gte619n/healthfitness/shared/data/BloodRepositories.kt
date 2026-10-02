package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.blood.BloodMarker
import com.gte619n.healthfitness.shared.domain.blood.BloodReading
import com.gte619n.healthfitness.shared.domain.blood.BloodTestReport
import com.gte619n.healthfitness.shared.domain.blood.UploadEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — the repository contracts the Blood/Labs feature
 * ViewModels depend on. KMP ports of the Android core-data interfaces:
 *
 *  - android/core-data/.../data/blood/BloodReadingRepository (manual readings +
 *    the reactive Room-mirror read)
 *  - android/core-data/.../data/blood/BloodTestReportRepository (lab-PDF list,
 *    per-report detail, PDF download, and the online-only multipart-SSE upload)
 *
 * SIBLING to [MedicationRepository] (the reference, in Repositories.kt) so the
 * reference file is not rewritten. The concrete implementations (Room-KMP reads +
 * Ktor writes + the multipart-SSE client) are the remaining Phase 1C body; the
 * Phase 3 VMs + SwiftUI depend only on these interfaces, so the vertical is
 * authored and unit-tested against fakes first.
 */

/** Manual blood readings, ported from Android `BloodReadingRepository`. */
interface BloodReadingRepository {
    /** Reactive Room-mirror read of all manual readings (offline-first). */
    fun observeReadings(): Flow<List<BloodReading>>

    /** Best-effort network revalidation into the mirror; never resets the UI. */
    suspend fun refresh()

    /**
     * Optimistic + outbox create of a manual reading. Mints a client id, writes a
     * PENDING mirror row that appears instantly, and enqueues the POST. Returns the
     * optimistic domain row (with a placeholder reference until the server stamps
     * the authoritative range on the next pull).
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

/** Lab-PDF reports, ported from Android `BloodTestReportRepository`. */
interface BloodTestReportRepository {
    /** Reactive Room-mirror read of extracted lab reports (offline-first). */
    fun observeReports(): Flow<List<BloodTestReport>>

    /** Best-effort network revalidation into the mirror; never resets the UI. */
    suspend fun refresh()

    /** Per-report detail (network). */
    suspend fun get(reportId: String): BloodTestReport

    /** Soft-delete a report; reflected locally so the list drops the row. */
    suspend fun delete(reportId: String)

    /** Download the report PDF bytes, resolving [pdfDownloadPath] against the base URL. */
    suspend fun downloadPdf(pdfDownloadPath: String): ByteArray

    /**
     * Online-only AI upload stream (multipart SSE, D17). Emits [UploadEvent]s as
     * the backend uploads → extracts → saves; on [UploadEvent.Complete] the mirror
     * is refreshed so the new report appears in [observeReports].
     */
    fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent>
}

/**
 * Reactive online/offline signal. The lab-PDF upload is an online-only AI flow
 * (D17) — the screen gates the picker behind this and shows a "needs connection"
 * affordance when false; nothing is queued offline. KMP port of Android's
 * `data.net.Connectivity`; the concrete impl wraps NWPathMonitor on iOS and
 * ConnectivityManager on Android (Phase 1C).
 */
interface ConnectivityMonitor {
    val isOnline: Flow<Boolean>
}
