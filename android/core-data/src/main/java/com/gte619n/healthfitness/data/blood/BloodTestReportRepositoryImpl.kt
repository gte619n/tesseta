package com.gte619n.healthfitness.data.blood

import com.gte619n.healthfitness.data.db.dao.BloodTestReportDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.gte619n.healthfitness.data.net.MultipartSseClient
import com.gte619n.healthfitness.data.net.SseEvent
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.BloodTestReportRepository
import com.gte619n.healthfitness.domain.blood.UploadEvent
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType

/**
 * IMPL-AND-20 (Phase 5) — the blood-test-report **list** reads from the
 * `bloodTestReports` mirror (D8); the network only fills it. The PDF **upload** is
 * an online-only AI flow (multipart SSE, D17) and stays on the network — on an
 * `UploadEvent.Complete` it [refresh]es the mirror so the new report appears.
 * Per-report detail ([get]) and PDF download also stay on the network. The mirror
 * `payloadJson` is the full [BloodTestReportDto].
 */
@Singleton
internal class BloodTestReportRepositoryImpl @Inject constructor(
    private val api: BloodApi,
    private val multipartSseClient: MultipartSseClient,
    private val dao: BloodTestReportDao,
    private val support: MirrorRepositorySupport,
    private val moshi: Moshi,
    @BackendBaseUrl private val baseUrl: String,
) : BloodTestReportRepository {

    private val dtoAdapter = moshi.adapter(BloodTestReportDto::class.java)

    override fun observeReports(): Flow<List<BloodTestReport>> = support.observe(
        rows = dao.observeActive(),
        decode = { json -> runCatching { dtoAdapter.fromJson(json)?.toDomain() }.getOrNull() },
        liveFallback = {
            api.listReports().map { it.toDomain() }
                .sortedByDescending { it.sampleDate ?: java.time.LocalDate.MIN }
        },
    )

    override suspend fun refresh() {
        if (support.killSwitchOn()) return
        val dtos = api.listReports()
        support.refreshInto(
            MirrorTables.BLOOD_TEST_REPORTS,
            dtos.map { dto ->
                MirrorRepositorySupport.RefreshRow(
                    id = dto.reportId,
                    payloadJson = dtoAdapter.toJson(dto),
                    lastUpdate = dto.toDomain().sampleDate
                        ?.let { it.toEpochDay() * 86_400_000L }
                        ?: System.currentTimeMillis(),
                )
            },
        )
    }

    override suspend fun get(reportId: String): BloodTestReport =
        api.getReport(reportId).toDomain()

    override suspend fun delete(reportId: String) {
        api.deleteReport(reportId)
        // Server soft-deletes; reflect it locally so the list drops the row.
        dao.markArchived(reportId, System.currentTimeMillis())
    }

    override suspend fun downloadPdf(pdfDownloadPath: String): ByteArray {
        // Resolve the relative download path against the backend base URL so the
        // Retrofit @Url call carries the full absolute address.
        val absolute = baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegments(pdfDownloadPath.trimStart('/'))
            .build()
            .toString()
        return api.downloadPdf(absolute).use { it.bytes() }
    }

    override fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent> {
        val url: HttpUrl = baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegments("api/me/blood/reports")
            .build()
        return multipartSseClient.stream(
            url = url,
            parts = listOf(
                MultipartSseClient.Part(
                    name = "file",
                    fileName = fileName,
                    contentType = "application/pdf".toMediaType(),
                    body = pdfBytes,
                ),
            ),
        )
            .map { event -> event.toUploadEvent(moshi) }
            .onEach { e -> if (e is UploadEvent.Complete) refresh() }
    }
}

/**
 * Phase payloads from BloodTestController's SSE stream:
 *   { "phase": "uploading", "message": "..." }
 *   { "phase": "extracting", "message": "..." }
 *   { "phase": "saving", "message": "..." }
 *   { "phase": "complete", "report": { ...BloodTestReportDto } }
 *   { "phase": "failed", "error": "..." }
 */
internal fun SseEvent.toUploadEvent(moshi: Moshi): UploadEvent {
    @Suppress("UNCHECKED_CAST")
    val payload = moshi.adapter(Map::class.java).fromJson(data) as? Map<String, Any?>
        ?: return UploadEvent.Failed("Empty event")
    return when (payload["phase"]) {
        "uploading" -> UploadEvent.Uploading
        "extracting" -> UploadEvent.Extracting
        "saving" -> UploadEvent.Saving
        "complete" -> {
            val dto = moshi.adapter(BloodTestReportDto::class.java)
                .fromJsonValue(payload["report"])
            if (dto == null) {
                UploadEvent.Failed("Missing report on complete event")
            } else {
                UploadEvent.Complete(dto.toDomain())
            }
        }
        "failed" -> UploadEvent.Failed(payload["error"]?.toString() ?: "Upload failed")
        else -> UploadEvent.Failed("Unknown phase: ${payload["phase"]}")
    }
}
