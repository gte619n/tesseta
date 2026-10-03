package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.blood.BloodMarker
import com.gte619n.healthfitness.shared.domain.blood.BloodReading
import com.gte619n.healthfitness.shared.domain.blood.BloodTestReport
import com.gte619n.healthfitness.shared.domain.blood.UploadEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first [BloodReadingRepository] over the
 * EXISTING backend (`GET/POST/DELETE api/me/blood`), the same endpoints Android's
 * `BloodApi` uses. Follows [HttpMedicationRepository]: a [MutableStateFlow] that
 * fetches lazily on first observe and on [refresh], so the Blood overview is
 * reactive to refreshes without (yet) reading the offline mirror.
 *
 * The shared `@Serializable` [BloodReading] domain shape matches the backend
 * `BloodReadingResponse` JSON 1:1 (field names + nullability — see Android
 * `BloodDtos.kt`), so the Ktor JSON decoder deserializes the responses directly;
 * no separate DTO layer is needed. The only shim is the create REQUEST body, which
 * is a distinct subset ([CreateReadingRequest]).
 */
class HttpBloodReadingRepository(private val client: HttpClient) : BloodReadingRepository {

    private val cache = MutableStateFlow<List<BloodReading>>(emptyList())
    private var loaded = false

    override fun observeReadings(): Flow<List<BloodReading>> = cache.onStart {
        if (!loaded) refresh()
    }

    override suspend fun refresh() {
        cache.value = client.get("api/me/blood").body()
        loaded = true
    }

    override suspend fun create(
        marker: BloodMarker,
        value: Double,
        unit: String?,
        sampleDate: LocalDate,
        labSource: String?,
        notes: String?,
    ): BloodReading {
        val created: BloodReading = client.post("api/me/blood") {
            setBody(
                CreateReadingRequest(
                    marker = marker.name,
                    value = value,
                    unit = unit,
                    sampleDate = sampleDate.toString(),
                    labSource = labSource,
                    notes = notes,
                ),
            )
        }.body()
        // Optimistic local append so the overview updates before the next pull.
        cache.value = (cache.value.filterNot { it.readingId == created.readingId } + created)
        return created
    }

    override suspend fun delete(readingId: String) {
        client.delete("api/me/blood/$readingId")
        cache.value = cache.value.filterNot { it.readingId == readingId }
    }

    /** Body for `POST api/me/blood` — mirrors Android's `CreateReadingRequestDto`. */
    @Serializable
    private data class CreateReadingRequest(
        val marker: String,
        val value: Double,
        val unit: String?,
        val sampleDate: String,
        val labSource: String?,
        val notes: String?,
    )
}

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first [BloodTestReportRepository] over
 * `GET/DELETE api/me/blood/reports` + the per-report GET and the PDF byte download.
 * The [upload] multipart-SSE path is NOT wired on iOS yet (there is no shared
 * multipart-SSE client — Android's `MultipartSseClient` has no KMP port); it emits
 * a single [UploadEvent.Failed] so the screen degrades gracefully. The Blood
 * overview itself needs only [observeReports]/[refresh]; the upload sheet is a
 * platform-stubbed affordance (document picker + SSE) tracked separately.
 */
class HttpBloodTestReportRepository(private val client: HttpClient) : BloodTestReportRepository {

    private val cache = MutableStateFlow<List<BloodTestReport>>(emptyList())
    private var loaded = false

    override fun observeReports(): Flow<List<BloodTestReport>> = cache.onStart {
        if (!loaded) refresh()
    }

    override suspend fun refresh() {
        cache.value = client.get("api/me/blood/reports").body()
        loaded = true
    }

    override suspend fun get(reportId: String): BloodTestReport =
        client.get("api/me/blood/reports/$reportId").body()

    override suspend fun delete(reportId: String) {
        client.delete("api/me/blood/reports/$reportId")
        cache.value = cache.value.filterNot { it.reportId == reportId }
    }

    override suspend fun downloadPdf(pdfDownloadPath: String): ByteArray =
        client.get(pdfDownloadPath.trimStart('/')).readRawBytes()

    override fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent> = flow {
        // Online-only multipart-SSE AI upload (D17). No shared KMP multipart-SSE
        // client exists yet; the lab-PDF upload sheet on iOS stays platform-stubbed.
        emit(UploadEvent.Failed("Lab PDF upload isn't available on iOS yet."))
    }
}
