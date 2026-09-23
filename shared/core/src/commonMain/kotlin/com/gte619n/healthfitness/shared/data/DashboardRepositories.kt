package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave A1 — repository interfaces the shared
 * [com.gte619n.healthfitness.shared.presentation.dashboard.DashboardViewModel]
 * observes. KMP ports of the Android `data.dashboard.*Repository` contracts.
 *
 * Kept in a sibling file to [Repositories.kt] (per the Wave A1 rules) so this
 * feature vertical owns its own interfaces without editing the reference file.
 *
 * The Android dashboard cards are backed by CONCRETE `@Inject` repositories (no
 * interfaces — single impl each), each exposing a cache-first pair:
 *   - `cached*()`  — local-only (Room/DataStore) read; returns the last-synced
 *     value instantly with NO network, or null/empty when nothing is cached yet.
 *   - `load*()`    — network revalidation; the offline-first loader seeds from
 *     the cache first, then revalidates and keeps the cached value on failure.
 * We mirror that exact `cached*`/`load*` shape here as interfaces so the shared
 * ViewModel replicates the offline-first, no-Loading-reset behaviour verbatim
 * and can be unit-tested with a fake before the concrete KMP store lands (1C).
 *
 * The dashboard-scoped domain models below are ports of Android's
 * `core-domain/.../domain/dashboard/Dashboard.kt` — intentionally
 * dashboard-scoped (their own package), NOT the canonical per-feature models.
 * They live here (co-located with the repos that produce them) rather than under
 * `domain/` because the Wave A1 rules confine writes to this file + the
 * presentation package; field names/nullability match the Android source 1:1.
 */

// ---------------------------------------------------------------------------
// Dashboard-scoped domain models (port of domain.dashboard.Dashboard.kt).
// java.time.{Instant,LocalDate} → kotlinx-datetime; fields identical to Android.
// ---------------------------------------------------------------------------

enum class BodyMetric { WEIGHT_KG, BODY_FAT_PERCENT, LEAN_MASS_KG, BMI }

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

/** Identity shown in the dashboard header avatar. Ported from Android's DashboardUser. */
data class DashboardUser(val initials: String, val photoUrl: String?)

/** Profile fields the dashboard header/vitals need (avatar + hidden biometrics). */
data class DashboardProfile(
    val displayName: String?,
    val photoUrl: String?,
    val hiddenBiometrics: Set<String>,
)

// ---------------------------------------------------------------------------
// Repository interfaces — one per live card, mirroring `data.dashboard.*`.
// Each pairs a local-only `cached*` read with a network `load*` revalidation.
// ---------------------------------------------------------------------------

interface DashboardBodyCompositionRepository {
    /** Local-only, no network — instant seed on cold start (null = nothing cached). */
    suspend fun cachedRecent(): WeightSummary?
    /** Network revalidation of the recent weight series. */
    suspend fun loadRecent(): WeightSummary?
}

interface DashboardDailyMetricsRepository {
    // Distinct names (vs body-composition's cachedRecent/loadRecent) so a single
    // class MAY implement several dashboard repos without a JVM signature clash.
    suspend fun cachedRecentMetrics(): List<DailyMetricPoint>
    suspend fun loadRecentMetrics(): List<DailyMetricPoint>
}

interface DashboardBloodMarkerRepository {
    suspend fun cachedDashboardMarkers(): List<BloodMarkerSummary>
    suspend fun loadDashboardMarkers(): List<BloodMarkerSummary>
}

interface DashboardNutritionRepository {
    suspend fun cachedToday(): NutritionDay?
    suspend fun loadToday(): NutritionDay
}

interface DashboardRecentActivityRepository {
    /** Persisted single-slot cache (no Room mirror for this server-derived aggregate). */
    suspend fun cachedRecentActivity(): List<RecentActivityEntry>?
    suspend fun loadRecentActivity(): List<RecentActivityEntry>
}

interface DashboardProfileRepository {
    /** Result-like get; the header falls back to fixtures on failure. */
    suspend fun get(): DashboardProfile?
}

/**
 * Today's workout pick, ported from Android's `TodayWorkoutViewModel`
 * dependencies. The in-progress draft is reactive off the local store; the
 * planned/completed pick is a best-effort calendar lookup refreshed on demand.
 */
interface DashboardWorkoutRepository {
    /** Reactive local drafts — a parked/active session appears the moment it lands. */
    fun observeDrafts(): kotlinx.coroutines.flow.Flow<List<WorkoutSessionDraft>>
    /** Resolve today's pick from the active program's calendar (network best-effort). */
    suspend fun resolveTodayWorkout(): TodayWorkout
}

/**
 * What the home "Today's workout" card should offer right now. Verbatim port of
 * Android's `mobile.dashboard.TodayWorkout` sealed interface (kept here with the
 * repo that resolves it — this feature vertical owns the dashboard model set).
 */
sealed interface TodayWorkout {
    /** No in-progress draft and nothing planned for today — render nothing. */
    data object Hidden : TodayWorkout

    /** An in-progress local draft can be resumed in one tap (ADR-0012 D1). */
    data class Resume(
        val programId: String,
        val scheduledId: String,
        val label: String?,
        val setsLogged: Int,
        /** When the session started — the card shows live (paused-aware) elapsed. */
        val startedAt: Instant,
    ) : TodayWorkout

    /**
     * A planned session ready to start in one tap. [isToday] is false when it's
     * the next upcoming (or a missed earlier) session rather than today's.
     */
    data class Start(
        val programId: String,
        val scheduledId: String,
        val label: String?,
        val isToday: Boolean,
    ) : TodayWorkout

    /**
     * Today's session is already done — the card shows a recap (volume, time,
     * sets, calorie estimate) instead of an action. Wins over [Start] on the day
     * of completion: right after finishing, the user wants their stats.
     */
    data class Completed(
        val programId: String,
        val scheduledId: String,
        val label: String?,
        /** Total seconds from start to finish (server-stamped at finish). */
        val durationSeconds: Int?,
        /** Count of all logged sets across every prescription. */
        val totalSets: Int,
        /** Sum of weight × reps across all logged sets, in pounds. */
        val totalWeightLbs: Double,
        /** MET-based burn estimate; null when there's no duration to estimate from. */
        val estimatedCalories: Int?,
    ) : TodayWorkout
}
