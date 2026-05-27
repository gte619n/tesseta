package com.gte619n.healthfitness.domain.bodycomposition

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Snapshot + raw-readings access for the body-composition surface. The
 * snapshot pre-computes the deltas + 90-day weight series the overview
 * screen needs so the VM/UI never re-computes the same math.
 *
 * Implementations live in `core-data/bodycomposition/`.
 */
interface BodyCompositionRepository {
    /** Latest snapshot + 90d weight series. Hot-replays on refresh. */
    fun observeSnapshot(): Flow<BodyCompositionSnapshot>

    /** Re-pulls the raw readings and re-emits a freshly computed snapshot. */
    suspend fun refresh()

    /** Direct point access for ad-hoc queries — used by tests + future charts. */
    suspend fun pointsInRange(
        metric: BodyCompositionMetric,
        from: Instant,
        to: Instant,
    ): List<BodyCompositionPoint>
}

/**
 * DEXA scan list + detail + mutation surface. Edits go via [patchField]
 * with the same `path` strings the web client uses
 * (`totalMassLb`, `trunk.leanTissueLb`, …). The repository returns the
 * updated scan so the caller can settle optimistic UI state to the
 * server's authoritative response.
 */
interface DexaScanRepository {
    fun observeScans(): Flow<List<DexaScanSummary>>
    suspend fun refreshScans()
    suspend fun getScan(scanId: String): DexaScan
    suspend fun deleteScan(scanId: String)

    /** Pull PDF bytes from the backend (binary `application/pdf`). */
    suspend fun downloadPdf(scanId: String): ByteArray

    /**
     * PATCH a single numeric field. `path` matches the web client's path
     * convention. Returns the updated scan. Throws on backend error so
     * the caller can revert optimistic state.
     */
    suspend fun patchField(scanId: String, path: String, value: Double?): DexaScan

    /** Multipart + SSE upload. See `UploadDexaViewModel` for the consumer. */
    fun uploadPdf(fileName: String, bytes: ByteArray): Flow<DexaUploadEvent>
}

/** SSE-driven progress events for the DEXA PDF upload. */
sealed interface DexaUploadEvent {
    /** "uploading" | "extracting" | "saving" — verbatim from backend. */
    data class Phase(val phase: String, val message: String?) : DexaUploadEvent
    data class Complete(val scan: DexaScan) : DexaUploadEvent
    data class Failed(val error: String) : DexaUploadEvent
}
