package com.gte619n.healthfitness.domain.bodycomposition

import kotlinx.coroutines.flow.Flow

interface DexaScanRepository {
    fun observeScans(): Flow<List<DexaScanSummary>>

    suspend fun refreshScans()

    suspend fun getScan(scanId: String): DexaScan

    suspend fun deleteScan(scanId: String)

    /** Pull PDF bytes from the backend. */
    suspend fun downloadPdf(scanId: String): ByteArray

    /**
     * PATCH a single numeric field. `path` matches the web client's path
     * convention (e.g. "totalMassLb", "trunk.leanTissueLb"). Returns the
     * updated scan. Throws on backend error so the caller can revert
     * optimistic state.
     */
    suspend fun patchField(scanId: String, path: String, value: Double?): DexaScan

    /** Multipart + SSE upload. See UploadDexaViewModel for the consumer. */
    fun uploadPdf(fileName: String, bytes: ByteArray): Flow<DexaUploadEvent>
}

sealed interface DexaUploadEvent {
    /** "uploading" | "extracting" | "saving" — verbatim from backend. */
    data class Phase(val phase: String, val message: String?) : DexaUploadEvent
    data class Complete(val scan: DexaScan) : DexaUploadEvent
    data class Failed(val error: String) : DexaUploadEvent
}
