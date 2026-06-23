package com.gte619n.healthfitness.domain.dashboard

import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import java.time.Instant
import java.time.LocalDate

// IMPL-AND-01: pure-Kotlin domain types for the dashboard's live-data cards.
// These are intentionally dashboard-scoped (their own package); the richer
// per-domain models in IMPL-AND-04/05 (blood / body-composition) are canonical
// for their feature screens.

enum class BodyMetric { WEIGHT_KG, BODY_FAT_PERCENT, LEAN_MASS_KG, BMI }

data class BodyCompositionPoint(
    val recordId: String,
    val metric: BodyMetric,
    val value: Double,
    val sampleTime: Instant,
    val sourcePlatform: String?,
    val recordingMethod: String?,
)

data class ChartXLabel(val xFraction: Float, val label: String)

data class WeightSummary(
    val latestLb: Double,
    val sevenDayDeltaLb: Double?,
    val ninetyDayDeltaLb: Double?,
    val series: List<Double>, // downsampled to ~30 points
    val yMin: Double,
    val yMax: Double,
    val xLabels: List<ChartXLabel>,
    val latestBodyFatPct: Double?,
    val latestLeanMassLb: Double?,
    val lastUpdatedAt: Instant?, // sampleTime of the most recent weigh-in
)

enum class MarkerTone { Good, Warn, Alert }

data class HistoryPoint(val date: LocalDate, val value: Double)

data class BloodMarkerSummary(
    val markerKey: String,
    val displayName: String,
    val value: Double,
    val unit: String,
    val tone: MarkerTone,
    val goodFillPct: Float,
    val goodLeftPct: Float,
    val tickPct: Float,
    val displayMin: Double,
    val goodThreshold: Double,
    val displayMax: Double,
    val history: List<HistoryPoint>,
)

enum class DoseWindow { MORNING, AFTERNOON, EVENING, BEDTIME }

data class TodaysDoseSummary(
    val medicationId: String,
    val drugName: String,
    val imageUrl: String?,
    val window: DoseWindow,
    val dose: Double,
    val unit: String?,
    val taken: Boolean,
    val takenAt: Instant?,
)

data class DailyMetricPoint(
    val date: LocalDate,
    val steps: Int?,
    val restingHeartRate: Int?,
    val sleepMinutes: Int?,
    val hrvMs: Int?,
    val sleepScore: Int?,
)

// The domain of the dashboard "Recent" feed: the user's latest activity merged
// across these sources by the backend. UNKNOWN keeps an older client forward-
// compatible if the backend adds a kind it doesn't recognize yet.
enum class RecentActivityKind { WORKOUT, WEIGH_IN, SLEEP, FOOD, MEDICATION, UNKNOWN }

data class RecentActivityEntry(
    val kind: RecentActivityKind,
    val title: String,
    val subtitle: String?,
    val timestamp: Instant,
)

// offline-fix: every card exposes a `cached*` reader that returns ONLY local
// (Room/DataStore) data and never touches the network, so the dashboard can seed
// each card with the last-known value instantly on a cold start — no spinner — and
// then revalidate via the `load*` (network) path. A null/empty result means
// "nothing cached", in which case the card stays on its loading state until the
// first network result lands (only relevant before the first sync).
interface DashboardBodyCompositionRepository {
    suspend fun loadRecent(): WeightSummary? // null = no data

    /** Local-only weight summary from the mirror, or null if nothing is cached. */
    suspend fun cachedRecent(): WeightSummary?
}

interface DashboardDailyMetricsRepository {
    /** Daily metrics for roughly the last 30 days, one entry per calendar day. */
    suspend fun loadRecent(): List<DailyMetricPoint>

    /** Local-only metrics from the mirror (empty if nothing is cached). */
    suspend fun cachedRecent(): List<DailyMetricPoint>
}

interface DashboardBloodMarkerRepository {
    suspend fun loadDashboardMarkers(): List<BloodMarkerSummary>

    /** Local-only blood markers from the mirror (empty if nothing is cached). */
    suspend fun cachedDashboardMarkers(): List<BloodMarkerSummary>
}

interface DashboardTodaysDosesRepository {
    suspend fun loadToday(): List<TodaysDoseSummary>

    /** Last-cached today's doses, or null if nothing cached for the current day. */
    suspend fun cachedToday(): List<TodaysDoseSummary>?
}

interface DashboardNutritionRepository {
    /** Today's logged nutrition: totals + target for the Today card's macro tier. */
    suspend fun loadToday(): NutritionDay

    /** Local-only nutrition day from the mirror, or null if nothing is cached. */
    suspend fun cachedToday(): NutritionDay?
}

interface DashboardRecentActivityRepository {
    /** Pull the latest cross-source activity rows, newest-first (server-capped). */
    suspend fun loadRecent(): List<RecentActivityEntry>

    /** The last feed persisted by [loadRecent], or null if none cached yet. */
    suspend fun cachedRecent(): List<RecentActivityEntry>?
}
