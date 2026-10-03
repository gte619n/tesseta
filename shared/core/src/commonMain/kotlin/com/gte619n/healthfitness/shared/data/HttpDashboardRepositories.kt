package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first impls of the seven
 * [com.gte619n.healthfitness.shared.data] dashboard repository interfaces the
 * shared `DashboardViewModel` observes. Ports of Android's
 * `data/dashboard/DashboardData.kt` (same backend endpoints + same derivation
 * mappers), following the sibling [HttpBloodReadingRepository] /
 * [HttpWorkoutProgramRepository] pattern.
 *
 * Endpoints (verified against Android `DashboardApi` + the backend controllers):
 *   - body-composition : GET api/me/body-composition      (list of metric rows)
 *   - daily-metrics    : GET api/me/daily-metrics?from&to  (per-day health rows)
 *   - blood            : GET api/me/blood                   (reading list + ref)
 *   - nutrition today  : the existing nutrition day repo    (same day endpoint)
 *   - recent-activity  : GET api/me/recent-activity?limit
 *   - profile          : GET api/me
 *   - today-workout    : the workout program repo's calendar + the session drafts
 *
 * There is NO on-device mirror read here (that's the deferred mirror-read pass —
 * see the nutrition/goals/blood/workouts decisions): the `cached*` methods return
 * null/empty so the VM falls straight through to the network `load*`. The card
 * stays on its last Loaded value if a revalidation fails (the VM's no-Loading-reset
 * invariant), so online-first still degrades gracefully; it's just not cold-start
 * offline-polished. The DERIVATION mappers (weight summary, blood marker summary)
 * are faithful ports of Android's so web/Android/iOS render identical cards.
 *
 * The response DTOs below are kept private (field-compatible with Android's
 * Moshi DTOs); the derived domain models are the shared dashboard-scoped types
 * in [DashboardRepositories.kt].
 */

// ---------------------------------------------------------------------------
// Response DTOs (field-compatible with Android's DashboardApi DTOs).
// ---------------------------------------------------------------------------

@Serializable
private data class BodyCompositionDto(
    val recordId: String,
    val metric: String,
    val value: Double,
    val sampleTime: Instant,
    val sourcePlatform: String? = null,
    val recordingMethod: String? = null,
)

@Serializable
private data class ReferenceDto(
    val unit: String,
    val orientation: String,
    val goodThreshold: Double,
    val displayMin: Double,
    val displayMax: Double,
)

@Serializable
private data class BloodReadingDto(
    val readingId: String,
    val marker: String,
    val value: Double,
    val unit: String,
    val sampleDate: String,
    val labSource: String? = null,
    val notes: String? = null,
    val reference: ReferenceDto,
)

@Serializable
private data class DailyMetricDto(
    val date: String,
    val steps: Int? = null,
    val restingHeartRate: Int? = null,
    val sleepMinutes: Int? = null,
    val hrvMs: Int? = null,
    val sleepScore: Int? = null,
)

@Serializable
private data class RecentActivityDto(
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val timestamp: Instant,
)

// ---------------------------------------------------------------------------
// Derivation mappers — verbatim ports of Android's DashboardData.kt mappers
// (java.time → kotlinx-datetime; logic + constants identical).
// ---------------------------------------------------------------------------

private object WeightSummaryMapper {
    private const val KG_TO_LB = 2.20462
    private const val TARGET_POINTS = 30
    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    private fun label(instant: Instant): String {
        val d = instant.toLocalDateUtc()
        return "${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth.toString().padStart(2, '0')}"
    }

    fun toWeightSummary(readings: List<BodyCompositionDto>, now: Instant): WeightSummary? {
        val weights = readings.filter { it.metric == "WEIGHT_KG" }.sortedBy { it.sampleTime }
        if (weights.size < 2) return null

        val windowStart = now.minusSecondsCompat(90L * 24 * 3600)
        var windowed = weights.filter { it.sampleTime >= windowStart }
        if (windowed.size < 2) windowed = weights

        val lbValues = windowed.map { it.value * KG_TO_LB }
        val series = downsample(lbValues, TARGET_POINTS)

        val latestLb = lbValues.last()
        val sevenAgo = now.minusSecondsCompat(7L * 24 * 3600)
        val nearest7 = windowed.minByOrNull { abs(it.sampleTime.epochSeconds - sevenAgo.epochSeconds) }
        val sevenDayDelta = nearest7?.let { latestLb - it.value * KG_TO_LB }
        val ninetyDayDelta = latestLb - lbValues.average()

        val minV = lbValues.min()
        val maxV = lbValues.max()
        val pad = ((maxV - minV).takeIf { it > 0 } ?: 1.0) * 0.15
        val yMin = minV - pad
        val yMax = maxV + pad

        val xLabels = listOf(0f, 0.33f, 0.66f, 1f).map { frac ->
            val idx = ((windowed.size - 1) * frac).toInt().coerceIn(0, windowed.size - 1)
            ChartXLabel(frac, label(windowed[idx].sampleTime))
        }

        val latestBodyFat = readings.filter { it.metric == "BODY_FAT_PERCENT" }
            .maxByOrNull { it.sampleTime }
        val latestLeanLb = latestBodyFat?.let { bf ->
            val pairWeight = weights.minByOrNull { abs(it.sampleTime.epochSeconds - bf.sampleTime.epochSeconds) }
            pairWeight?.takeIf { abs(it.sampleTime.epochSeconds - bf.sampleTime.epochSeconds) <= 6 * 3600 }
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
            lastUpdatedAt = weights.last().sampleTime,
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

private object BloodMarkerSummaryMapper {
    private val DISPLAY_ORDER = listOf("TESTOSTERONE", "LDL", "APO_B", "HBA1C")
    private val LABELS = mapOf(
        "TESTOSTERONE" to "Testosterone",
        "LDL" to "LDL",
        "APO_B" to "ApoB",
        "HBA1C" to "HbA1c",
    )

    fun toDashboardMarkers(readings: List<BloodReadingDto>, today: LocalDate): List<BloodMarkerSummary> {
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
                abs(latest.value - ref.goodThreshold) / (ref.goodThreshold.takeIf { it != 0.0 } ?: 1.0) < 0.15 -> MarkerTone.Warn
                else -> MarkerTone.Alert
            }
            val cutoff = today.minusDaysCompat(365)
            val history = list.mapNotNull { r ->
                runCatching { LocalDate.parse(r.sampleDate) }.getOrNull()?.let { it to r.value }
            }.filter { it.first >= cutoff }
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

private fun RecentActivityDto.toDomain(): RecentActivityEntry = RecentActivityEntry(
    kind = runCatching { RecentActivityKind.valueOf(kind.uppercase()) }
        .getOrDefault(RecentActivityKind.UNKNOWN),
    title = title,
    subtitle = subtitle,
    timestamp = timestamp,
)

private fun List<DailyMetricDto>.toDailyMetricPoints(): List<DailyMetricPoint> =
    mapNotNull { dto ->
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

// Small date/time helpers bridging java.time ergonomics the Android ports used.
private fun Instant.minusSecondsCompat(seconds: Long): Instant =
    Instant.fromEpochSeconds(epochSeconds - seconds, nanosecondsOfSecond)

private fun Instant.toLocalDateUtc(): LocalDate =
    toLocalDateUtcImpl()

private fun Instant.toLocalDateUtcImpl(): LocalDate {
    val days = (epochSeconds / 86_400L).toInt()
    return LocalDate.fromEpochDays(if (epochSeconds < 0 && epochSeconds % 86_400L != 0L) days - 1 else days)
}

private fun LocalDate.minusDaysCompat(days: Int): LocalDate =
    LocalDate.fromEpochDays(toEpochDays() - days)

// ---------------------------------------------------------------------------
// Repository impls.
// ---------------------------------------------------------------------------

private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

class HttpDashboardBodyCompositionRepository(
    private val client: HttpClient,
) : DashboardBodyCompositionRepository {
    override suspend fun cachedRecent(): WeightSummary? = null
    override suspend fun loadRecent(): WeightSummary? {
        val dtos: List<BodyCompositionDto> = client.get("api/me/body-composition").body()
        return WeightSummaryMapper.toWeightSummary(dtos, Clock.System.now())
    }
}

class HttpDashboardDailyMetricsRepository(
    private val client: HttpClient,
) : DashboardDailyMetricsRepository {
    override suspend fun cachedRecentMetrics(): List<DailyMetricPoint> = emptyList()
    override suspend fun loadRecentMetrics(): List<DailyMetricPoint> {
        val to = today()
        val from = to.minusDaysCompat(30)
        val dtos: List<DailyMetricDto> = client.get("api/me/daily-metrics") {
            parameter("from", from.toString())
            parameter("to", to.toString())
        }.body()
        return dtos.toDailyMetricPoints()
    }
}

class HttpDashboardBloodMarkerRepository(
    private val client: HttpClient,
) : DashboardBloodMarkerRepository {
    override suspend fun cachedDashboardMarkers(): List<BloodMarkerSummary> = emptyList()
    override suspend fun loadDashboardMarkers(): List<BloodMarkerSummary> {
        val dtos: List<BloodReadingDto> = client.get("api/me/blood").body()
        return BloodMarkerSummaryMapper.toDashboardMarkers(dtos, today())
    }
}

/**
 * Reuses the shared [NutritionDayRepository] (same `day` endpoint as the
 * Nutrition Today screen) so the dashboard card and the full screen never
 * disagree. `cachedToday` serves the repo's offline cache; `loadToday` fetches.
 */
class HttpDashboardNutritionRepository(
    private val nutrition: NutritionDayRepository,
) : DashboardNutritionRepository {
    override suspend fun cachedToday(): NutritionDay? =
        runCatching { nutrition.cachedDay(today().toString()) }.getOrNull()

    override suspend fun loadToday(): NutritionDay =
        nutrition.day(today().toString())
}

class HttpDashboardRecentActivityRepository(
    private val client: HttpClient,
) : DashboardRecentActivityRepository {
    // No persisted single-slot cache on iOS yet (Android uses a DataStore); the
    // VM keeps the last Loaded feed on a failed revalidation regardless.
    override suspend fun cachedRecentActivity(): List<RecentActivityEntry>? = null
    override suspend fun loadRecentActivity(): List<RecentActivityEntry> {
        val dtos: List<RecentActivityDto> = client.get("api/me/recent-activity") {
            parameter("limit", 5)
        }.body()
        return dtos.map { it.toDomain() }
    }
}

class HttpDashboardProfileRepository(
    private val profile: ProfileRepository,
) : DashboardProfileRepository {
    override suspend fun get(): DashboardProfile? =
        profile.get().getOrNull()?.let { p ->
            DashboardProfile(
                displayName = p.displayName,
                photoUrl = p.photoUrl,
                hiddenBiometrics = p.hiddenBiometrics.toSet(),
            )
        }
}

/**
 * Today's workout pick — a port of Android's `TodayWorkoutViewModel`
 * dependencies. [observeDrafts] delegates to the mirror-backed session repo (so a
 * parked/active local draft resumes reactively); [resolveTodayWorkout] resolves
 * today's calendar pick from the ACTIVE program (completed-recap wins, else the
 * planned/next session), computing the recap exactly as Android does.
 */
class HttpDashboardWorkoutRepository(
    private val programs: WorkoutProgramRepository,
    private val sessions: MirrorWorkoutSessionRepository,
    private val bodyComp: DashboardBodyCompositionRepository,
) : DashboardWorkoutRepository {

    override fun observeDrafts(): Flow<List<com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft>> =
        sessions.observeDrafts()

    override suspend fun resolveTodayWorkout(): TodayWorkout {
        val today = today()
        // The reactive programs flow fetches lazily on first collect (online-first).
        val active = programs.observePrograms().first().firstOrNull { it.status == ProgramStatus.ACTIVE }
            ?: return TodayWorkout.Hidden

        // Monday-anchored current week (kotlinx DayOfWeek.ordinal: Mon=0 … Sun=6).
        val weekStart = today.minusDaysCompat(today.dayOfWeek.ordinal)
        val weekEnd = weekStart.plusDaysCompat(6)
        // observeCalendar is a one-shot fetch flow (emits the window once).
        val calendar = programs.observeCalendar(active.programId, weekStart, weekEnd).first()

        val completedToday = calendar
            .firstOrNull { it.date == today && it.status == ScheduledStatus.COMPLETED }
        if (completedToday != null) return buildCompleted(active.programId, completedToday)

        val planned = calendar.filter { it.status == ScheduledStatus.PLANNED }
        val chosen = planned.firstOrNull { it.date == today }
            ?: planned.filter { it.date >= today }.minByOrNull { it.date }
            ?: planned.minByOrNull { it.date }
        return chosen?.let {
            TodayWorkout.Start(
                programId = active.programId,
                scheduledId = it.scheduledId,
                label = it.dayLabel.ifBlank { null },
                isToday = it.date == today,
            )
        } ?: TodayWorkout.Hidden
    }

    private suspend fun buildCompleted(programId: String, session: ScheduledWorkout): TodayWorkout.Completed {
        val prescriptions = session.session?.blocks?.flatMap { it.prescriptions }.orEmpty()
        val sets = prescriptions.sumOf { it.loggedSets.size }
        val volume = prescriptions.sumOf { rx ->
            rx.loggedSets.sumOf { (it.weightLbs ?: 0.0) * (it.reps ?: 0) }
        }
        val bodyWeightLb = runCatching { bodyComp.cachedRecent() }.getOrNull()?.latestLb
        return TodayWorkout.Completed(
            programId = programId,
            scheduledId = session.scheduledId,
            label = session.dayLabel.ifBlank { null },
            durationSeconds = session.durationSeconds,
            totalSets = sets,
            totalWeightLbs = volume,
            estimatedCalories = estimateCalories(session.durationSeconds, bodyWeightLb),
        )
    }

    private fun estimateCalories(durationSeconds: Int?, bodyWeightLb: Double?): Int? {
        if (durationSeconds == null || durationSeconds <= 0) return null
        val kg = (bodyWeightLb ?: DEFAULT_BODY_WEIGHT_LB) * LB_TO_KG
        val minutes = durationSeconds / 60.0
        return (STRENGTH_MET * 3.5 * kg / 200.0 * minutes).roundToInt()
    }

    private fun LocalDate.plusDaysCompat(days: Int): LocalDate =
        LocalDate.fromEpochDays(toEpochDays() + days)

    private companion object {
        const val LB_TO_KG = 0.453592
        const val STRENGTH_MET = 5.0
        const val DEFAULT_BODY_WEIGHT_LB = 175.0
    }
}
