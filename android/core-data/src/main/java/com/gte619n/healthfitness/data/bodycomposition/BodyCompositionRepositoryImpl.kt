package com.gte619n.healthfitness.data.bodycomposition

import com.gte619n.healthfitness.data.bodycomposition.api.BodyCompositionApi
import com.gte619n.healthfitness.data.bodycomposition.dto.toDomainOrNull
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BodyCompositionRepositoryImpl @Inject constructor(
    private val api: BodyCompositionApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BodyCompositionRepository {

    private val snapshot = MutableSharedFlow<BodyCompositionSnapshot>(replay = 1)

    override fun observeSnapshot(): Flow<BodyCompositionSnapshot> = snapshot.asSharedFlow()

    override suspend fun refresh() {
        val points = withContext(io) { api.list().mapNotNull { it.toDomainOrNull() } }
        snapshot.emit(buildSnapshot(points))
    }

    override suspend fun pointsInRange(
        metric: BodyCompositionMetric,
        from: Instant,
        to: Instant,
    ): List<BodyCompositionPoint> = withContext(io) {
        api.list(from.toString(), to.toString(), metric.name)
            .mapNotNull { it.toDomainOrNull() }
            .filter { it.metric == metric }
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
}
