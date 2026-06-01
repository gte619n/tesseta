package com.gte619n.healthfitness.data.bodycomposition

import com.gte619n.healthfitness.data.bodycomposition.api.DexaScanApi
import com.gte619n.healthfitness.data.bodycomposition.dto.DexaScanDto
import com.gte619n.healthfitness.data.bodycomposition.dto.PatchFieldRequest
import com.gte619n.healthfitness.data.bodycomposition.dto.toDomain
import com.gte619n.healthfitness.data.bodycomposition.dto.toSummary
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.gte619n.healthfitness.data.net.MultipartSseClient
import com.gte619n.healthfitness.data.net.SseEvent
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.domain.bodycomposition.DexaUploadEvent
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DexaScanRepositoryImpl @Inject constructor(
    private val api: DexaScanApi,
    private val multipartSseClient: MultipartSseClient,
    @BackendBaseUrl private val baseUrl: String,
    private val moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DexaScanRepository {

    private val scans = MutableSharedFlow<List<DexaScanSummary>>(replay = 1)

    private val eventAdapter = moshi.adapter(DexaUploadPayload::class.java)

    override fun observeScans(): Flow<List<DexaScanSummary>> = scans.asSharedFlow()

    override suspend fun refreshScans() {
        val summaries = withContext(io) { api.list().map { it.toSummary() } }
        scans.emit(summaries)
    }

    override suspend fun getScan(scanId: String): DexaScan =
        withContext(io) { api.get(scanId).toDomain() }

    override suspend fun deleteScan(scanId: String) {
        withContext(io) { api.delete(scanId) }
        refreshScans()
    }

    override suspend fun patchField(scanId: String, path: String, value: Double?): DexaScan =
        withContext(io) { api.patchField(scanId, PatchFieldRequest(path, value)).toDomain() }

    override suspend fun downloadPdf(scanId: String): ByteArray =
        withContext(io) { api.downloadPdf(scanId).use { it.bytes() } }

    override fun uploadPdf(fileName: String, bytes: ByteArray): Flow<DexaUploadEvent> {
        val url: HttpUrl = uploadUrl()
        val parts = listOf(
            MultipartSseClient.Part(
                name = "file",
                fileName = fileName,
                contentType = "application/pdf".toMediaType(),
                body = bytes,
            ),
        )
        return multipartSseClient.stream(url, parts).map { event -> event.toDexaEvent() }
    }

    private fun uploadUrl(): HttpUrl {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return (normalized + "api/me/dexa/scans").toHttpUrl()
    }

    private fun SseEvent.toDexaEvent(): DexaUploadEvent {
        val payload = runCatching { eventAdapter.fromJson(data) }.getOrNull()
            ?: return DexaUploadEvent.Failed("Malformed upload event")
        payload.error?.let { return DexaUploadEvent.Failed(it) }
        payload.scan?.let { return DexaUploadEvent.Complete(it.toDomain()) }
        val phase = payload.phase ?: event ?: "uploading"
        if (phase == "failed") {
            return DexaUploadEvent.Failed(payload.message ?: "Upload failed")
        }
        if (phase == "complete") {
            // Complete with no scan body — surface as a failure so the UI can recover.
            return DexaUploadEvent.Failed(payload.message ?: "Upload completed without a scan")
        }
        return DexaUploadEvent.Phase(phase, payload.message)
    }

    /** Wire shape of each SSE `data:` line: `{ phase, message?, scan?, error? }`. */
    internal data class DexaUploadPayload(
        val phase: String?,
        val message: String?,
        val scan: DexaScanDto?,
        val error: String?,
    )
}
