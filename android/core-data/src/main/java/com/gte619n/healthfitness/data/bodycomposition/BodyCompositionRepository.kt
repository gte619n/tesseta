package com.gte619n.healthfitness.data.bodycomposition

import com.gte619n.healthfitness.data.bodycomposition.api.BodyCompositionApi
import com.gte619n.healthfitness.data.bodycomposition.dto.BodyCompositionReadingDto
import com.gte619n.healthfitness.data.bodycomposition.dto.toDomainOrNull
import com.gte619n.healthfitness.data.db.dao.BodyCompositionDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — Room-backed, offline-first body-composition repository.
 *
 * Body composition is **pull-only / server-derived** (Google-Health-sourced,
 * D9): there is no write path, so this domain never touches the outbox. The read
 * path (D8) now observes the `bodyComposition` mirror table and derives the
 * [BodyCompositionSnapshot] from the locally-stored points, so the dashboard and
 * body-comp screen render from Room with the network offline. [refresh] fills the
 * mirror with the latest readings; the background SyncEngine pull keeps it fresh.
 *
 * The mirror `payloadJson` is the full [BodyCompositionReadingDto] (every field
 * the snapshot math consumes), so nothing the screen needs is lost vs. the
 * sanitized delta `doc`. When the kill-switch is latched (D13) the snapshot falls
 * back to a one-shot live-network fetch.
 */
@Singleton
class BodyCompositionRepository @Inject constructor(
    private val api: BodyCompositionApi,
    private val dao: BodyCompositionDao,
    private val support: MirrorRepositorySupport,
    private val moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val dtoAdapter = moshi.adapter(BodyCompositionReadingDto::class.java)

    fun observeSnapshot(): Flow<BodyCompositionSnapshot> =
        support.observe(
            rows = dao.observeActive(),
            decode = { json -> runCatching { dtoAdapter.fromJson(json)?.toDomainOrNull() }.getOrNull() },
            liveFallback = { api.list().mapNotNull { it.toDomainOrNull() } },
        ).map { points -> buildSnapshot(points) }

    suspend fun refresh() {
        if (support.killSwitchOn()) return
        val dtos = withContext(io) { api.list() }
        support.refreshInto(
            MirrorTables.BODY_COMPOSITION,
            dtos.mapNotNull { dto ->
                val recordId = dto.recordId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val metric = dto.metric?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // Key on the Firestore doc id / sync key ("{METRIC}__{recordId}"),
                // not the bare recordId. A single weigh-in emits a WEIGHT_KG and a
                // BODY_FAT_PERCENT reading that share one recordId; keying on the bare
                // value collapses the pair into one row, so most of the weight series
                // gets clobbered by body-fat (and refresh disagreed with the sync key).
                MirrorRepositorySupport.RefreshRow(
                    id = "${metric}__$recordId",
                    payloadJson = dtoAdapter.toJson(dto),
                    lastUpdate = dto.sampleTime.toEpochMillisOrZero(),
                )
            },
        )
    }

    suspend fun pointsInRange(
        metric: BodyCompositionMetric,
        from: Instant,
        to: Instant,
    ): List<BodyCompositionPoint> = withContext(io) {
        // #30: back the windowed read with a date-ranged Room query so the chart
        // renders offline. Under the kill-switch (D13) Room is not the source of
        // truth, so fall back to the live network range fetch.
        if (support.killSwitchOn()) {
            return@withContext api.list(from.toString(), to.toString(), metric.name)
                .mapNotNull { it.toDomainOrNull() }
                .filter { it.metric == metric }
                .sortedBy { it.sampleTime }
        }
        // Fill the mirror on a cold miss so the first windowed read after a fresh
        // install isn't empty (the background pull keeps it fresh thereafter).
        if (dao.observeActive().first().isEmpty()) refresh()
        dao.pointsInRange(from.toEpochMilli(), to.toEpochMilli())
            .mapNotNull { runCatching { dtoAdapter.fromJson(it.payloadJson)?.toDomainOrNull() }.getOrNull() }
            .filter { it.metric == metric && !it.sampleTime.isBefore(from) && !it.sampleTime.isAfter(to) }
            .sortedBy { it.sampleTime }
    }

    /** Aggregates raw points into the snapshot the UI consumes. */
    internal fun buildSnapshot(points: List<BodyCompositionPoint>): BodyCompositionSnapshot {
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
        val sevenDayDelta = deltaOver(weightSeries, latestWeight, Duration.ofDays(7))
        val ninetyDayDelta = deltaOver(weightSeries, latestWeight, Duration.ofDays(90))

        val series90d = latestWeightTime?.let { now ->
            val cutoff = now.minus(Duration.ofDays(90))
            weightSeries.filter { !it.sampleTime.isBefore(cutoff) }
        } ?: weightSeries

        val bodyFatSeries = points
            .filter { it.metric == BodyCompositionMetric.BODY_FAT_PERCENT }
            .sortedBy { it.sampleTime }

        // Anchor the 90-day window on the latest weight time, falling back to the
        // latest body-fat time when there's no weight, so both series line up.
        val bodyFatAnchor = latestWeightTime ?: latestBodyFat?.sampleTime
        val series90dBodyFat = bodyFatAnchor?.let { now ->
            val cutoff = now.minus(Duration.ofDays(90))
            bodyFatSeries.filter { !it.sampleTime.isBefore(cutoff) }
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

    /**
     * Delta = latest value minus the value of the reading closest to
     * (latest - window). Returns null when there's no reading old enough to
     * anchor the window.
     */
    private fun deltaOver(
        series: List<BodyCompositionPoint>,
        latest: BodyCompositionPoint?,
        window: Duration,
    ): Double? {
        if (latest == null || series.size < 2) return null
        val target = latest.sampleTime.minus(window)
        // Only consider readings at or before the window start.
        val candidate = series
            .filter { it.sampleTime <= latest.sampleTime && !it.sampleTime.isAfter(target) }
            .maxByOrNull { it.sampleTime }
            ?: return null
        return latest.value - candidate.value
    }

    private fun String?.toEpochMillisOrZero(): Long =
        this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}
