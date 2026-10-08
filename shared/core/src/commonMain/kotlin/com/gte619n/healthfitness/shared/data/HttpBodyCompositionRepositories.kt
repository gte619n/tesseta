package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.shared.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.shared.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.shared.presentation.bodycomposition.DexaUploadEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first [BodyCompositionRepository] over
 * `GET api/me/body-composition` (the same endpoint Android's `BodyCompositionApi`
 * hits). Body composition is pull-only / server-derived (D9) — NO write path, so
 * this never touches the outbox.
 *
 * The backend returns raw per-metric readings; the [BodyCompositionSnapshot] the
 * overview consumes is DERIVED here, porting Android's
 * `BodyCompositionRepository.buildSnapshot` (latest-per-metric + 7d/90d deltas +
 * the 90-day weight & body-fat series). The wire rows are decoded through a lenient
 * [ReadingDto] (nullable fields) and incomplete rows are dropped — a faithful port
 * of Android's `toDomainOrNull`.
 */
class HttpBodyCompositionRepository(private val client: HttpClient) : BodyCompositionRepository {

    private val cache = MutableStateFlow(emptySnapshot())
    private var loaded = false

    override fun observeSnapshot(): Flow<BodyCompositionSnapshot> = cache.onStart {
        if (!loaded) refresh()
    }

    override suspend fun refresh() {
        val dtos: List<ReadingDto> = client.get("api/me/body-composition").body()
        cache.value = buildSnapshot(dtos.mapNotNull { it.toPointOrNull() })
        loaded = true
    }

    /** Wire row from `GET api/me/body-composition`; mirrors Android's `BodyCompositionReadingDto`. */
    @Serializable
    private data class ReadingDto(
        val recordId: String? = null,
        val metric: String? = null,
        val value: Double? = null,
        val sampleTime: String? = null,
        val sourcePlatform: String? = null,
        val recordingMethod: String? = null,
    ) {
        fun toPointOrNull(): BodyCompositionPoint? {
            val m = parseMetric(metric) ?: return null
            val v = value ?: return null
            val t = sampleTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            return BodyCompositionPoint(
                recordId = recordId ?: "",
                metric = m,
                value = v,
                sampleTime = t,
                sourcePlatform = sourcePlatform,
                recordingMethod = recordingMethod,
            )
        }
    }

    private companion object {
        private const val DAY_MS = 86_400_000L

        private fun parseMetric(raw: String?): BodyCompositionMetric? = when (raw) {
            "WEIGHT_KG" -> BodyCompositionMetric.WEIGHT_KG
            "BODY_FAT_PERCENT" -> BodyCompositionMetric.BODY_FAT_PERCENT
            "LEAN_MASS_KG" -> BodyCompositionMetric.LEAN_MASS_KG
            "BMI" -> BodyCompositionMetric.BMI
            else -> null
        }

        private fun emptySnapshot() = BodyCompositionSnapshot(
            latestWeightKg = null,
            latestBodyFatPercent = null,
            latestLeanMassKg = null,
            latestBmi = null,
            latestSampleTime = null,
            sevenDayDeltaKg = null,
            ninetyDayDeltaKg = null,
            series90d = emptyList(),
            series90dBodyFat = emptyList(),
        )

        /**
         * Aggregates raw points into the UI snapshot. Port of Android's
         * `BodyCompositionRepository.buildSnapshot` (java.time.Duration → epoch-ms
         * arithmetic on kotlinx `Instant`).
         */
        fun buildSnapshot(points: List<BodyCompositionPoint>): BodyCompositionSnapshot {
            fun latest(metric: BodyCompositionMetric): BodyCompositionPoint? =
                points.filter { it.metric == metric }.maxByOrNull { it.sampleTime }

            val latestWeight = latest(BodyCompositionMetric.WEIGHT_KG)
            val latestBodyFat = latest(BodyCompositionMetric.BODY_FAT_PERCENT)
            val latestLean = latest(BodyCompositionMetric.LEAN_MASS_KG)
            val latestBmi = latest(BodyCompositionMetric.BMI)

            val weightSeries = points
                .filter { it.metric == BodyCompositionMetric.WEIGHT_KG }
                .sortedBy { it.sampleTime }

            val latestWeightTime = latestWeight?.sampleTime
            val sevenDayDelta = deltaOver(weightSeries, latestWeight, 7 * DAY_MS)
            val ninetyDayDelta = deltaOver(weightSeries, latestWeight, 90 * DAY_MS)

            val series90d = latestWeightTime?.let { now ->
                val cutoff = now.minusMs(90 * DAY_MS)
                weightSeries.filter { it.sampleTime >= cutoff }
            } ?: weightSeries

            val bodyFatSeries = points
                .filter { it.metric == BodyCompositionMetric.BODY_FAT_PERCENT }
                .sortedBy { it.sampleTime }

            val bodyFatAnchor = latestWeightTime ?: latestBodyFat?.sampleTime
            val series90dBodyFat = bodyFatAnchor?.let { now ->
                val cutoff = now.minusMs(90 * DAY_MS)
                bodyFatSeries.filter { it.sampleTime >= cutoff }
            } ?: bodyFatSeries

            val latestSampleTime = listOfNotNull(
                latestWeight?.sampleTime,
                latestBodyFat?.sampleTime,
                latestLean?.sampleTime,
                latestBmi?.sampleTime,
            ).maxOrNull()

            return BodyCompositionSnapshot(
                latestWeightKg = latestWeight?.value,
                latestBodyFatPercent = latestBodyFat?.value,
                latestLeanMassKg = latestLean?.value,
                latestBmi = latestBmi?.value,
                latestSampleTime = latestSampleTime,
                sevenDayDeltaKg = sevenDayDelta,
                ninetyDayDeltaKg = ninetyDayDelta,
                series90d = series90d,
                series90dBodyFat = series90dBodyFat,
            )
        }

        private fun Instant.minusMs(ms: Long): Instant =
            Instant.fromEpochMilliseconds(toEpochMilliseconds() - ms)

        private fun deltaOver(
            series: List<BodyCompositionPoint>,
            latest: BodyCompositionPoint?,
            windowMs: Long,
        ): Double? {
            if (latest == null || series.size < 2) return null
            val target = latest.sampleTime.minusMs(windowMs)
            val candidate = series
                .filter { it.sampleTime <= latest.sampleTime && it.sampleTime <= target }
                .maxByOrNull { it.sampleTime }
                ?: return null
            return latest.value - candidate.value
        }
    }
}

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first [DexaScanRepository] over
 * `GET/DELETE api/me/dexa/scans`, the per-scan GET, the field PATCH, and the PDF
 * byte download — matching Android's `DexaScanApi`. The shared `@Serializable`
 * [DexaScanSummary]/[DexaScan] domain shapes match the backend JSON 1:1, so they
 * decode directly. [uploadPdf] (online-only multipart-SSE, D17) has no shared KMP
 * client yet; it emits a single [DexaUploadEvent.Failed] so the upload sheet
 * degrades gracefully (platform-stubbed, tracked separately).
 */
class HttpDexaScanRepository(private val client: HttpClient) : DexaScanRepository {

    private val cache = MutableStateFlow<List<DexaScanSummary>>(emptyList())
    private var loaded = false

    override fun observeScans(): Flow<List<DexaScanSummary>> = cache.onStart {
        if (!loaded) refreshScans()
    }

    override suspend fun refreshScans() {
        cache.value = client.get("api/me/dexa/scans").body()
        loaded = true
    }

    override suspend fun getScan(scanId: String): DexaScan =
        client.get("api/me/dexa/scans/$scanId").body()

    override suspend fun deleteScan(scanId: String) {
        client.delete("api/me/dexa/scans/$scanId")
        cache.value = cache.value.filterNot { it.scanId == scanId }
        runCatching { refreshScans() }
    }

    override suspend fun patchField(scanId: String, path: String, value: Double?): DexaScan {
        val scan: DexaScan = client.patch("api/me/dexa/scans/$scanId/field") {
            setBody(PatchFieldRequest(path, value))
        }.body()
        runCatching { refreshScans() }
        return scan
    }

    override suspend fun downloadPdf(scanId: String): ByteArray =
        client.get("api/me/dexa/scans/$scanId/pdf").readRawBytes()

    override fun uploadPdf(fileName: String, bytes: ByteArray): Flow<DexaUploadEvent> = flow {
        emit(DexaUploadEvent.Failed("DEXA PDF upload isn't available on iOS yet."))
    }

    /** Body for `PATCH api/me/dexa/scans/{scanId}/field`; mirrors Android's `PatchFieldRequest`. */
    @Serializable
    private data class PatchFieldRequest(val path: String, val value: Double?)
}
