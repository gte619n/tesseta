package com.gte619n.healthfitness.domain.dashboard

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

interface DashboardBodyCompositionRepository {
    suspend fun loadRecent(): WeightSummary? // null = no data
}

interface DashboardBloodMarkerRepository {
    suspend fun loadDashboardMarkers(): List<BloodMarkerSummary>
}

interface DashboardTodaysDosesRepository {
    suspend fun loadToday(): List<TodaysDoseSummary>
}
