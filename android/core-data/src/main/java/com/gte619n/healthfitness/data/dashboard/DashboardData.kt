package com.gte619n.healthfitness.data.dashboard

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.ChartXLabel
import com.gte619n.healthfitness.domain.dashboard.DailyMetricPoint
import com.gte619n.healthfitness.domain.dashboard.DashboardBloodMarkerRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardDailyMetricsRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardNutritionRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardRecentActivityRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardTodaysDosesRepository
import com.gte619n.healthfitness.domain.dashboard.DoseWindow
import com.gte619n.healthfitness.domain.dashboard.HistoryPoint
import com.gte619n.healthfitness.domain.dashboard.MarkerTone
import com.gte619n.healthfitness.domain.dashboard.RecentActivityEntry
import com.gte619n.healthfitness.domain.dashboard.RecentActivityKind
import com.gte619n.healthfitness.domain.dashboard.TodaysDoseSummary
import com.gte619n.healthfitness.domain.dashboard.WeightSummary
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
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
    suspend fun todaysDoses(@Query("date") date: String? = null): List<TodaysDoseDto>

    @GET("api/me/daily-metrics")
    suspend fun dailyMetrics(
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<DailyMetricDto>

    @GET("api/me/recent-activity")
    suspend fun recentActivity(@Query("limit") limit: Int = 5): List<RecentActivityDto>
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

internal data class DailyMetricDto(
    val date: String,
    val steps: Int?,
    val restingHeartRate: Int?,
    val sleepMinutes: Int?,
    val hrvMs: Int?,
    val sleepScore: Int?,
)

internal data class RecentActivityDto(
    val kind: String,
    val title: String,
    val subtitle: String?,
    val timestamp: Instant,
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

internal fun RecentActivityDto.toDomain(): RecentActivityEntry = RecentActivityEntry(
    // Unknown kinds degrade to UNKNOWN rather than dropping the row, so an older
    // client still shows new activity types (with a generic icon).
    kind = runCatching { RecentActivityKind.valueOf(kind.uppercase()) }
        .getOrDefault(RecentActivityKind.UNKNOWN),
    title = title,
    subtitle = subtitle,
    timestamp = timestamp,
)

internal object DailyMetricMapper {
    fun toDomain(rows: List<DailyMetricDto>): List<DailyMetricPoint> =
        rows.mapNotNull { dto ->
            val date = runCatching { LocalDate.parse(dto.date) }.getOrNull() ?: return@mapNotNull null
            DailyMetricPoint(
                date = date,
                steps = dto.steps,
                restingHeartRate = dto.restingHeartRate,
                sleepMinutes = dto.sleepMinutes,
                hrvMs = dto.hrvMs,
                sleepScore = dto.sleepScore,
            )
        }.sortedBy { it.date }
}

// ---- Repository impls ----

/**
 * IMPL-AND-20 (#30) — Room-backed dashboard weight summary.
 *
 * The weight trend is a windowed/derived read; it now reads the `bodyComposition`
 * mirror over a date-ranged Room query so the dashboard card renders offline. The
 * window is the last ~120 days (a comfortable superset of the 90-day summary
 * window so the 7/90-day deltas have an anchor). Under the kill-switch (D13) it
 * falls back to the live network list.
 */
internal class DashboardBodyCompositionRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    private val dao: com.gte619n.healthfitness.data.db.dao.BodyCompositionDao,
    private val support: com.gte619n.healthfitness.data.sync.MirrorRepositorySupport,
    moshi: com.squareup.moshi.Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardBodyCompositionRepository {
    private val dtoAdapter = moshi.adapter(BodyCompositionDto::class.java)

    override suspend fun loadRecent(): WeightSummary? = withContext(io) {
        if (support.killSwitchOn()) {
            return@withContext BodyCompositionMapper.toWeightSummary(api.bodyComposition())
        }
        // Refresh the FULL weight history from the network every load, then serve
        // from Room. The body-composition mirror is also populated by the sync
        // engine, but only within its recent (14-day) window — so a fill-on-empty
        // guard left the dashboard stuck on the handful of recent points and never
        // showing (or updating) the real trend. /api/me/body-composition returns
        // the user's full history; refreshInto upserts it idempotently. (Mirrors
        // DashboardDailyMetricsRepositoryImpl, which refreshes on every call.)
        runCatching { fillFromNetwork() }
        summaryFromRoom()
    }

    // offline-fix: local-only seed — the same Room read [loadRecent] serves, minus
    // the network refresh, so the card shows the last-synced weight instantly.
    override suspend fun cachedRecent(): WeightSummary? = withContext(io) {
        if (support.killSwitchOn()) return@withContext null
        summaryFromRoom()
    }

    private suspend fun summaryFromRoom(): WeightSummary? {
        val now = Instant.now()
        val from = now.minusSeconds(120L * 24 * 3600)
        val rows = dao.pointsInRange(from.toEpochMilli(), now.toEpochMilli())
            .mapNotNull { runCatching { dtoAdapter.fromJson(it.payloadJson) }.getOrNull() }
        return BodyCompositionMapper.toWeightSummary(rows, now)
    }

    private suspend fun fillFromNetwork() {
        val dtos = api.bodyComposition()
        support.refreshInto(
            com.gte619n.healthfitness.data.db.entity.MirrorTables.BODY_COMPOSITION,
            dtos.map {
                com.gte619n.healthfitness.data.sync.MirrorRepositorySupport.RefreshRow(
                    // recordId is the sample timestamp and is NOT unique across
                    // metrics — a scale reports WEIGHT_KG and BODY_FAT_PERCENT at
                    // the same instant, so they share a recordId. Keying the mirror
                    // row by recordId alone made those collide and clobber each
                    // other (REPLACE), collapsing the whole weight series down to a
                    // single point. Key by "<metric>__<recordId>" — the same id the
                    // backend uses (and the sync engine mirrors), so fill + sync
                    // dedupe instead of fighting.
                    id = "${it.metric}__${it.recordId}",
                    payloadJson = dtoAdapter.toJson(it),
                    lastUpdate = it.sampleTime.toEpochMilli(),
                )
            },
        )
    }
}

/**
 * IMPL-AND-20 (Phase 5) — Room-backed daily metrics (pull-only, D9).
 *
 * Daily metrics are Google-Health-sourced and never written from the client, so
 * this domain reads from the `dailyMetrics` mirror (D8) and NEVER enqueues to the
 * outbox. [loadRecent] fills the mirror from the 30-day network window (filling on
 * a cold miss, then refreshing) and serves the rows from Room, so the dashboard
 * renders the trend offline. Each row's `payloadJson` is the full [DailyMetricDto]
 * (every field the chart consumes), keyed by date.
 */
internal class DashboardDailyMetricsRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    private val dao: com.gte619n.healthfitness.data.db.dao.DailyMetricDao,
    private val support: com.gte619n.healthfitness.data.sync.MirrorRepositorySupport,
    moshi: com.squareup.moshi.Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardDailyMetricsRepository {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val dtoAdapter = moshi.adapter(DailyMetricDto::class.java)

    override suspend fun loadRecent(): List<DailyMetricPoint> = withContext(io) {
        val today = LocalDate.now()
        val from = today.minusDays(30)
        if (support.killSwitchOn()) {
            return@withContext DailyMetricMapper.toDomain(
                api.dailyMetrics(from.format(dateFmt), today.format(dateFmt)),
            )
        }
        // Refresh the mirror from the recent window (idempotent; won't clobber dirty
        // rows — daily metrics are never dirty since they're pull-only), then serve
        // from Room.
        runCatching {
            val dtos = api.dailyMetrics(from.format(dateFmt), today.format(dateFmt))
            support.refreshInto(
                com.gte619n.healthfitness.data.db.entity.MirrorTables.DAILY_METRICS,
                dtos.map {
                    com.gte619n.healthfitness.data.sync.MirrorRepositorySupport.RefreshRow(
                        id = it.date,
                        payloadJson = dtoAdapter.toJson(it),
                        lastUpdate = runCatching { LocalDate.parse(it.date).toEpochDay() * 86_400_000L }
                            .getOrDefault(System.currentTimeMillis()),
                    )
                },
            )
        }
        metricsFromRoom(from)
    }

    // offline-fix: local-only seed — Room read without the network refresh.
    override suspend fun cachedRecent(): List<DailyMetricPoint> = withContext(io) {
        if (support.killSwitchOn()) return@withContext emptyList()
        metricsFromRoom(LocalDate.now().minusDays(30))
    }

    private suspend fun metricsFromRoom(from: LocalDate): List<DailyMetricPoint> {
        val rows = dao.observeActive().first()
            .mapNotNull { runCatching { dtoAdapter.fromJson(it.payloadJson) }.getOrNull() }
            .filter { it.date >= from.format(dateFmt) }
            .sortedBy { it.date }
        return DailyMetricMapper.toDomain(rows)
    }
}

/**
 * IMPL-AND-20 (#30) — Room-backed dashboard blood-marker trend.
 *
 * Reads the `bloodReadings` mirror over a date-ranged Room query (the marker
 * summary uses up to a 365-day history) so the dashboard renders offline. The
 * mirror payload is the blood repo's reading DTO, whose JSON is field-compatible
 * with the dashboard DTO. Under the kill-switch (D13) it falls back to live.
 */
internal class DashboardBloodMarkerRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    private val dao: com.gte619n.healthfitness.data.db.dao.BloodReadingDao,
    private val support: com.gte619n.healthfitness.data.sync.MirrorRepositorySupport,
    moshi: com.squareup.moshi.Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardBloodMarkerRepository {
    private val dtoAdapter = moshi.adapter(BloodReadingDto::class.java)

    override suspend fun loadDashboardMarkers(): List<BloodMarkerSummary> = withContext(io) {
        if (support.killSwitchOn()) {
            return@withContext BloodMarkerSummaryMapper.toDashboardMarkers(api.bloodReadings())
        }
        if (dao.observeActive().first().isEmpty()) runCatching { fillFromNetwork() }
        markersFromRoom()
    }

    // offline-fix: local-only seed — Room read without the cold-miss network fill.
    override suspend fun cachedDashboardMarkers(): List<BloodMarkerSummary> = withContext(io) {
        if (support.killSwitchOn()) return@withContext emptyList()
        markersFromRoom()
    }

    private suspend fun markersFromRoom(): List<BloodMarkerSummary> {
        val now = LocalDate.now()
        val from = now.minusDays(365)
        val fromMillis = from.toEpochDay() * 86_400_000L
        val toMillis = now.toEpochDay() * 86_400_000L + 86_400_000L // inclusive of today
        val rows = dao.readingsInRange(fromMillis, toMillis)
            .mapNotNull { runCatching { dtoAdapter.fromJson(it.payloadJson) }.getOrNull() }
        return BloodMarkerSummaryMapper.toDashboardMarkers(rows)
    }

    private suspend fun fillFromNetwork() {
        val dtos = api.bloodReadings()
        support.refreshInto(
            com.gte619n.healthfitness.data.db.entity.MirrorTables.BLOOD_READINGS,
            dtos.map {
                com.gte619n.healthfitness.data.sync.MirrorRepositorySupport.RefreshRow(
                    id = it.readingId,
                    payloadJson = dtoAdapter.toJson(it),
                    lastUpdate = runCatching { LocalDate.parse(it.sampleDate).toEpochDay() * 86_400_000L }
                        .getOrDefault(System.currentTimeMillis()),
                )
            },
        )
    }
}

// offline-fix: today's doses are a server-derived projection (no syncable Room
// entity), so — like the recent-activity feed — they get a single-slot DataStore
// cache. Every successful load overwrites the cache; the dashboard seeds the card
// from the cache instantly on a cold/offline open (only for the current day) and
// then revalidates. Cleared on sign-out via SignOutSideEffects.
internal class DashboardTodaysDosesRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    private val cache: DashboardDosesCache,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardTodaysDosesRepository {
    override suspend fun loadToday(): List<TodaysDoseSummary> = withContext(io) {
        // Device-local date so the dashboard dose card resets on the user's day.
        val today = LocalDate.now().toString()
        val doses = api.todaysDoses(today).map { it.toDomain() }
        cache.write(today, doses)
        doses
    }

    override suspend fun cachedToday(): List<TodaysDoseSummary>? = withContext(io) {
        cache.read(LocalDate.now().toString())
    }
}

// Reuses the feature NutritionRepository (same getDay endpoint as the nutrition
// Today screen) so the dashboard card and the full screen never disagree. The
// returned day carries both totals and the macro target.
internal class DashboardNutritionRepositoryImpl @Inject constructor(
    private val nutrition: NutritionRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardNutritionRepository {
    override suspend fun loadToday(): NutritionDay = withContext(io) {
        // Device-local date so the card rolls over on the user's day.
        nutrition.day(LocalDate.now().toString())
    }

    // offline-fix: local-only seed — the mirror-backed day with no network.
    override suspend fun cachedToday(): NutritionDay? = withContext(io) {
        nutrition.cachedDay(LocalDate.now().toString())
    }
}

private val Context.recentActivityStore by preferencesDataStore("hf-recent-activity")

/**
 * Single-slot persistent cache for the dashboard "Recent" feed.
 *
 * The feed is a server-derived aggregate — there's no syncable entity to put in
 * the Room mirror (no stable id / lastUpdate, and it's read-only), so it lives in
 * its own small DataStore like the other key/value caches (auth token, unit
 * prefs, sync flags). It holds only the latest feed so a cold/offline open can
 * render the last-known activity instantly while a fresh pull runs.
 *
 * Cleared on sign-out via [com.gte619n.healthfitness.data.db.DbWipe]'s sibling
 * hook — this DataStore is NOT the encrypted Room DB, so it needs an explicit
 * wipe to avoid leaking one account's activity to the next on a shared device.
 */
@Singleton
class RecentActivityCache @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) {
    private val key = stringPreferencesKey("feed")
    private val adapter = moshi.adapter<List<RecentActivityEntry>>(
        Types.newParameterizedType(List::class.java, RecentActivityEntry::class.java),
    )

    /** The last persisted feed, or null if nothing has been cached yet. */
    suspend fun read(): List<RecentActivityEntry>? {
        val json = context.recentActivityStore.data.first()[key] ?: return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
    }

    suspend fun write(entries: List<RecentActivityEntry>) {
        context.recentActivityStore.edit { it[key] = adapter.toJson(entries) }
    }

    suspend fun clear() {
        context.recentActivityStore.edit { it.remove(key) }
    }
}

private val Context.dashboardDosesStore by preferencesDataStore("hf-dashboard-doses")

/**
 * offline-fix: single-slot persistent cache for the dashboard "Today's doses" card.
 *
 * Today's doses are a server-derived projection (medication schedule × adherence)
 * with no stable syncable entity to mirror in Room, so — like [RecentActivityCache]
 * — they live in their own small DataStore. It holds only the latest day's list,
 * tagged with the device-local date, so a cold/offline open renders the last-known
 * doses instantly while a fresh pull runs. A cache from a previous day is ignored
 * (returns null) so the card never shows yesterday's checklist.
 *
 * Cleared on sign-out via [com.gte619n.healthfitness.data.db.DbWipe]'s sibling hook
 * (this DataStore is NOT the encrypted Room DB) to avoid leaking one account's
 * doses to the next on a shared device.
 */
@Singleton
class DashboardDosesCache @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) {
    private val keyDate = stringPreferencesKey("date")
    private val keyList = stringPreferencesKey("doses")
    private val adapter = moshi.adapter<List<TodaysDoseSummary>>(
        Types.newParameterizedType(List::class.java, TodaysDoseSummary::class.java),
    )

    /** Cached doses for [date], or null if the cache is empty or for another day. */
    suspend fun read(date: String): List<TodaysDoseSummary>? {
        val prefs = context.dashboardDosesStore.data.first()
        if (prefs[keyDate] != date) return null
        val json = prefs[keyList] ?: return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
    }

    suspend fun write(date: String, doses: List<TodaysDoseSummary>) {
        context.dashboardDosesStore.edit {
            it[keyDate] = date
            it[keyList] = adapter.toJson(doses)
        }
    }

    suspend fun clear() {
        context.dashboardDosesStore.edit { it.clear() }
    }
}

// The recent feed is a derived cross-source aggregate the backend assembles per
// request, so there's nothing stable to mirror in Room. Instead it's persisted
// to a single-slot DataStore ([RecentActivityCache]): every successful pull
// overwrites the cache, and the ViewModel renders the cached feed instantly on
// open / when offline while a fresh pull runs (stale-while-revalidate).
internal class DashboardRecentActivityRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    private val cache: RecentActivityCache,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DashboardRecentActivityRepository {
    override suspend fun loadRecent(): List<RecentActivityEntry> = withContext(io) {
        val entries = api.recentActivity().map { it.toDomain() }
        cache.write(entries)
        entries
    }

    override suspend fun cachedRecent(): List<RecentActivityEntry>? = withContext(io) {
        cache.read()
    }
}

// ---- Hilt ----

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DashboardDataModule {
    @Binds @Singleton
    abstract fun bindBodyComp(impl: DashboardBodyCompositionRepositoryImpl): DashboardBodyCompositionRepository

    @Binds @Singleton
    abstract fun bindDailyMetrics(impl: DashboardDailyMetricsRepositoryImpl): DashboardDailyMetricsRepository

    @Binds @Singleton
    abstract fun bindBlood(impl: DashboardBloodMarkerRepositoryImpl): DashboardBloodMarkerRepository

    @Binds @Singleton
    abstract fun bindDoses(impl: DashboardTodaysDosesRepositoryImpl): DashboardTodaysDosesRepository

    @Binds @Singleton
    abstract fun bindNutrition(impl: DashboardNutritionRepositoryImpl): DashboardNutritionRepository

    @Binds @Singleton
    abstract fun bindRecentActivity(impl: DashboardRecentActivityRepositoryImpl): DashboardRecentActivityRepository

    companion object {
        @Provides @Singleton
        fun provideDashboardApi(retrofit: Retrofit): DashboardApi =
            retrofit.create(DashboardApi::class.java)
    }
}
