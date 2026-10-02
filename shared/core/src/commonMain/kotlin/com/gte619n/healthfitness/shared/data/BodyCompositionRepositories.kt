package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.shared.presentation.bodycomposition.DexaUploadEvent
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-IOS-01 Phase 3 Wave E2 — the repository contracts the Body-Composition
 * feature ViewModels depend on. KMP ports of the Android core-data interfaces:
 *
 *  - android/core-data/.../data/bodycomposition/BodyCompositionRepository
 *    (pull-only, server-derived Google-Health weight/body-fat snapshot)
 *  - android/core-data/.../data/bodycomposition/DexaScanRepository (DEXA scan
 *    list, per-scan detail, optimistic field patch, PDF download, and the
 *    online-only multipart-SSE upload)
 *
 * Body composition has NO write path (D9) — it never touches the outbox. DEXA
 * detail edits are optimistic PATCHes. [ConnectivityMonitor] (BloodRepositories.kt)
 * is reused to gate the online-only DEXA upload.
 */

/** Pull-only body-composition snapshot, ported from Android `BodyCompositionRepository`. */
interface BodyCompositionRepository {
    /** Reactive Room-mirror-derived snapshot (weight/body-fat/lean/BMI + 90d series). */
    fun observeSnapshot(): Flow<BodyCompositionSnapshot>

    /** Best-effort network revalidation into the mirror; never resets the UI. */
    suspend fun refresh()
}

/** DEXA scans, ported from Android `DexaScanRepository`. */
interface DexaScanRepository {
    /** Reactive Room-mirror read of the DEXA scan grid summaries (offline-first). */
    fun observeScans(): Flow<List<DexaScanSummary>>

    /** Best-effort network revalidation into the mirror; never resets the UI. */
    suspend fun refreshScans()

    /** Full per-scan detail (network). */
    suspend fun getScan(scanId: String): DexaScan

    /** Delete a scan, then refresh the mirror so the grid drops the row. */
    suspend fun deleteScan(scanId: String)

    /**
     * PATCH a single (possibly region-scoped) numeric field. Path convention
     * matches the backend: `"totalMassLb"` or `"trunk.leanTissueLb"`. Returns the
     * server-authoritative scan; the VM applies the value optimistically first.
     */
    suspend fun patchField(scanId: String, path: String, value: Double?): DexaScan

    /** Download the scan's source PDF bytes. */
    suspend fun downloadPdf(scanId: String): ByteArray

    /**
     * Online-only AI upload stream (multipart SSE, D17). Emits [DexaUploadEvent]s
     * as the backend uploads → extracts → saves the parsed scan.
     */
    fun uploadPdf(fileName: String, bytes: ByteArray): Flow<DexaUploadEvent>
}
