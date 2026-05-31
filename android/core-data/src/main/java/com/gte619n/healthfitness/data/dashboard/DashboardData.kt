package com.gte619n.healthfitness.data.dashboard

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.ChartXLabel
import com.gte619n.healthfitness.domain.dashboard.DashboardBloodMarkerRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardTodaysDosesRepository
import com.gte619n.healthfitness.domain.dashboard.DoseWindow
import com.gte619n.healthfitness.domain.dashboard.HistoryPoint
import com.gte619n.healthfitness.domain.dashboard.MarkerTone
import com.gte619n.healthfitness.domain.dashboard.TodaysDoseSummary
import com.gte619n.healthfitness.domain.dashboard.WeightSummary
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.http.GET
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

// ---- Retrofit ----

internal interface DashboardApi {
    @GET("api/me/body-composition")
    suspend fun bodyComposition(): List<BodyCompositionDto>

    @GET("api/me/blood")
    suspend fun bloodReadings(): List<BloodReadingDto>

    @GET("api/me/medications/today")
    suspend fun todaysDoses(): List<TodaysDoseDto>
}

// ---- DTOs (Moshi reflection) ----

internal data class BodyCompositionDto(
    val recordId: String,
    val metric: String,
    val value: Double,
    val sampleTime: Instant,
    val sourcePlatform: String?,
    val recordingMethod: String?,
)

internal data class BloodReadingDto(
    val readingId: String,
    val marker: String,
    val value: Double,
    val unit: String,
    val sampleDate: String,
    val labSource: String?,
    val notes: String?,
    val reference: ReferenceDto,
)

internal data class ReferenceDto(
    val unit: String,
    val orientation: String,
    val goodThreshold: Double,
    val displayMin: Double,
    val displayMax: Double,
)

internal data class TodaysDoseDto(
    val medicationId: String,
    val drugName: String,
    val imageUrl: String?,
    val window: String,
    val dose: Double,
    val unit: String?,
    val taken: Boolean,
    val takenAt: Instant?,
)

// ---- Mappers ----

internal object BodyCompositionMapper {
    private const val KG_TO_LB = 2.20462
    private const val TARGET_POINTS = 30
    private val LABEL_FMT = DateTimeFormatter.ofPattern("MMM dd", Locale.US).withZone(ZoneOffset.UTC)

    fun toWeightSummary(readings: List<BodyCompositionDto>, now: Instant = Instant.now()): WeightSummary? {
        val weights = readings.filter { it.metric == "WEIGHT_KG" }
            .sortedBy { it.sampleTime }
        if (weights.size < 2) return null

        val windowStart = now.minusSeconds(90L * 24 * 3600)
        var windowed = weights.filter { !it.sampleTime.isBefore(windowStart) }
        if (windowed.size < 2) windowed = weights

        val lbValues = windowed.map { it.value * KG_TO_LB }
        val series = downsample(lbValues, TARGET_POINTS)

        val latestLb = lbValues.last()
        val sevenAgo = now.minusSeconds(7L * 24 * 3600)
        val nearest7 = windowed.minByOrNull { abs(it.sampleTime.epochSecond - sevenAgo.epochSecond) }
        val sevenDayDelta = nearest7?.let { latestLb - it.value * KG_TO_LB }
        val ninetyDayDelta = latestLb - lbValues.average()

        val minV = lbValues.min()
        val maxV = lbValues.max()
        val pad = ((maxV - minV).takeIf { it > 0 } ?: 1.0) * 0.15
        val yMin = minV - pad
        val yMax = maxV + pad

        val xLabels = listOf(0f, 0.33f, 0.66f, 1f).map { frac ->
            val idx = ((windowed.size - 1) * frac).toInt().coerceIn(0, windowed.size - 1)
            ChartXLabel(frac, LABEL_FMT.format(windowed[idx].sampleTime))
        }

        val latestBodyFat = readings.filter { it.metric == "BODY_FAT_PERCENT" }
            .maxByOrNull { it.sampleTime }
        val latestLeanLb = latestBodyFat?.let { bf ->
            val pairWeight = weights.minByOrNull { abs(it.sampleTime.epochSecond - bf.sampleTime.epochSecond) }
            pairWeight?.takeIf { abs(it.sampleTime.epochSecond - bf.sampleTime.epochSecond) <= 6 * 3600 }
                ?.let { it.value * KG_TO_LB * (1 - bf.value / 100.0) }
        }

        return WeightSummary(
            latestLb = latestLb,
            sevenDayDeltaLb = sevenDayDelta,
            ninetyDayDeltaLb = ninetyDayDelta,
            series = series,
            yMin = yMin,
            yMax = yMax,
            xLabels = xLabels,
            latestBodyFatPct = latestBodyFat?.value,
            latestLeanMassLb = latestLeanLb,
        )
    }

    private fun downsample(values: List<Double>, target: Int): List<Double> {
        if (values.size <= target) return values
        val bucket = values.size.toDouble() / target
        return (0 until target).map { i ->
            val start = (i * bucket).toInt()
            val end = ((i + 1) * bucket).toInt().coerceAtMost(values.size)
            values.subList(start, end.coerceAtLeast(start + 1)).average()
        }
    }
}

internal object BloodMarkerSummaryMapper {
    private val DISPLAY_ORDER = listOf("TESTOSTERONE", "LDL", "APO_B", "HBA1C")
    private val LABELS = mapOf(
        "TESTOSTERONE" to "Testosterone",
        "LDL" to "LDL",
        "APO_B" to "ApoB",
        "HBA1C" to "HbA1c",
    )

    fun toDashboardMarkers(readings: List<BloodReadingDto>): List<BloodMarkerSummary> {
        val byMarker = readings.groupBy { it.marker.uppercase() }
        return DISPLAY_ORDER.mapNotNull { key ->
            val list = byMarker[key]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val latest = list.maxByOrNull { it.sampleDate } ?: return@mapNotNull null
            val ref = latest.reference
            val span = (ref.displayMax - ref.displayMin).takeIf { it != 0.0 } ?: 1.0
            val lowerIsBetter = ref.orientation.equals("LOWER_IS_BETTER", ignoreCase = true)
            val tickPct = (((latest.value - ref.displayMin) / span).toFloat()).coerceIn(0f, 1f)
            val goodLeftPct = if (lowerIsBetter) 0f else (((ref.goodThreshold - ref.displayMin) / span).toFloat()).coerceIn(0f, 1f)
            val goodFillPct = if (lowerIsBetter) {
                (((ref.goodThreshold - ref.displayMin) / span).toFloat()).coerceIn(0f, 1f)
            } else {
                1f - goodLeftPct
            }
            val onGoodSide = if (lowerIsBetter) latest.value <= ref.goodThreshold else latest.value >= ref.goodThreshold
            val tone = when {
                onGoodSide -> MarkerTone.Good
                abs(latest.value - ref.goodThreshold) / ref.goodThreshold.takeIf { it != 0.0 }!! < 0.15 -> MarkerTone.Warn
                else -> MarkerTone.Alert
            }
            val cutoff = LocalDate.now().minusDays(365)
            val history = list.mapNotNull { r ->
                runCatching { LocalDate.parse(r.sampleDate) }.getOrNull()?.let { it to r.value }
            }.filter { !it.first.isBefore(cutoff) }
                .sortedBy { it.first }
                .associate { it.first to it.second }
                .map { HistoryPoint(it.key, it.value) }

            BloodMarkerSummary(
                markerKey = key,
                displayName = LABELS[key] ?: key,
                value = latest.value,
                unit = latest.unit,
                tone = tone,
                goodFillPct = goodFillPct,
                goodLeftPct = goodLeftPct,
                tickPct = tickPct,
                displayMin = ref.displayMin,
                goodThreshold = ref.goodThreshold,
                displayMax = ref.displayMax,
                history = history,
            )
        }
    }
}

internal fun TodaysDoseDto.toDomain(): TodaysDoseSummary = TodaysDoseSummary(
    medicationId = medicationId,
    drugName = drugName,
    imageUrl = imageUrl,
    window = runCatching { DoseWindow.valueOf(window.uppercase()) }.getOrDefault(DoseWindow.MORNING),
    dose = dose,
    unit = unit,
    taken = taken,
    takenAt = takenAt,
)

// ---- Repository impls ----

internal class DashboardBodyCompositionRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardBodyCompositionRepository {
    override suspend fun loadRecent(): WeightSummary? = withContext(io) {
        BodyCompositionMapper.toWeightSummary(api.bodyComposition())
    }
}

internal class DashboardBloodMarkerRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardBloodMarkerRepository {
    override suspend fun loadDashboardMarkers(): List<BloodMarkerSummary> = withContext(io) {
        BloodMarkerSummaryMapper.toDashboardMarkers(api.bloodReadings())
    }
}

internal class DashboardTodaysDosesRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardTodaysDosesRepository {
    override suspend fun loadToday(): List<TodaysDoseSummary> = withContext(io) {
        api.todaysDoses().map { it.toDomain() }
    }
}

// ---- Hilt ----

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DashboardDataModule {
    @Binds @Singleton
    abstract fun bindBodyComp(impl: DashboardBodyCompositionRepositoryImpl): DashboardBodyCompositionRepository

    @Binds @Singleton
    abstract fun bindBlood(impl: DashboardBloodMarkerRepositoryImpl): DashboardBloodMarkerRepository

    @Binds @Singleton
    abstract fun bindDoses(impl: DashboardTodaysDosesRepositoryImpl): DashboardTodaysDosesRepository

    companion object {
        @Provides @Singleton
        fun provideDashboardApi(retrofit: Retrofit): DashboardApi =
            retrofit.create(DashboardApi::class.java)
    }
}
