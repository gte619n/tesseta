package com.gte619n.healthfitness.mobile.dashboard

import com.gte619n.healthfitness.domain.dashboard.DailyMetricPoint
import kotlin.math.abs
import kotlin.math.roundToInt

// Builds the live daily-metric vital tiles (Resting HR / HRV / Sleep / Steps)
// from the dashboard's daily-metric series. Mirrors the web rules:
//  - series = non-null values, oldest→newest
//  - latest = last value
//  - delta = latest − mean(prior values within the 7-day window); omitted when
//    there is no prior data point
//  - sparkline = up to the last 9 non-null values, normalised into 0..20 space
//  - "lowerBetter" flips the delta tone/arrow (used for Resting HR)
//  - when there is no non-null data: Resting HR / HRV fall back to the matching
//    DashboardFallbacks.vitals entry; Steps / Sleep render value "—" with no
//    delta and a flat sparkline.

private const val SPARK_FLAT = 11f

/** A single metric series resolved from the raw daily-metric points. */
private data class MetricSeries(val dates: List<java.time.LocalDate>, val values: List<Double>)

private fun seriesOf(points: List<DailyMetricPoint>, select: (DailyMetricPoint) -> Int?): MetricSeries {
    val rows = points.sortedBy { it.date }.mapNotNull { p -> select(p)?.let { p.date to it.toDouble() } }
    return MetricSeries(rows.map { it.first }, rows.map { it.second })
}

/**
 * Delta of the latest value vs the mean of prior values that fall within the
 * 7 days before the latest reading. Null when there is no qualifying prior data.
 */
private fun sevenDayDelta(series: MetricSeries): Double? {
    if (series.values.size < 2) return null
    val latest = series.values.last()
    val latestDate = series.dates.last()
    val windowStart = latestDate.minusDays(7)
    val priors = series.dates.dropLast(1).zip(series.values.dropLast(1))
        .filter { !it.first.isBefore(windowStart) }
        .map { it.second }
    if (priors.isEmpty()) return null
    return latest - priors.average()
}

/** Normalises up to the last 9 non-null values into the 0..20 sparkline space. */
private fun metricSparkline(values: List<Double>): List<Float> {
    if (values.isEmpty()) return List(9) { SPARK_FLAT }
    val tail = values.takeLast(9)
    if (tail.size == 1) return List(9) { SPARK_FLAT }
    val min = tail.min()
    val max = tail.max()
    val span = (max - min).takeIf { it != 0.0 } ?: return List(tail.size) { SPARK_FLAT }
    return tail.map { v -> (2.0 + ((v - min) / span) * 16.0).toFloat() }
}

private fun delta(d: Double?, lowerBetter: Boolean, format: (Double) -> String): VitalDelta? {
    val v = d ?: return null
    val down = v <= 0
    val good = if (lowerBetter) down else !down
    return VitalDelta(
        direction = if (down) ArrowDir.Down else ArrowDir.Up,
        value = format(abs(v)),
        window = "7d",
        tone = if (good) Tone.Good else Tone.Warn,
    )
}

fun restingHrVital(points: List<DailyMetricPoint>): Vital {
    val s = seriesOf(points) { it.restingHeartRate }
    if (s.values.isEmpty()) return DashboardFallbacks.vitals[2] // "Resting HR"
    return Vital(
        label = "Resting HR",
        icon = DashboardIcons.Heart,
        value = s.values.last().roundToInt().toString(),
        unit = "bpm",
        delta = delta(sevenDayDelta(s), lowerBetter = true) { it.roundToInt().toString() },
        sparkline = metricSparkline(s.values),
    )
}

fun hrvVital(points: List<DailyMetricPoint>): Vital {
    val s = seriesOf(points) { it.hrvMs }
    if (s.values.isEmpty()) return DashboardFallbacks.vitals[1] // "HRV"
    return Vital(
        label = "HRV",
        icon = DashboardIcons.ActivityHeartbeat,
        value = s.values.last().roundToInt().toString(),
        unit = "ms",
        delta = delta(sevenDayDelta(s), lowerBetter = false) { it.roundToInt().toString() },
        sparkline = metricSparkline(s.values),
    )
}

fun sleepVital(points: List<DailyMetricPoint>): Vital {
    val s = seriesOf(points) { it.sleepMinutes }
    if (s.values.isEmpty()) {
        return Vital(
            label = "Sleep",
            icon = DashboardIcons.Moon,
            value = "—",
            unit = "h",
            delta = null,
            sparkline = List(9) { SPARK_FLAT },
        )
    }
    val hours = s.values.map { it / 60.0 }
    val hoursSeries = MetricSeries(s.dates, hours)
    return Vital(
        label = "Sleep",
        icon = DashboardIcons.Moon,
        value = "%.1f".format(hours.last()),
        unit = "h",
        delta = delta(sevenDayDelta(hoursSeries), lowerBetter = false) { "%.1f".format(it) },
        sparkline = metricSparkline(hours),
    )
}

fun stepsVital(points: List<DailyMetricPoint>): Vital {
    val s = seriesOf(points) { it.steps }
    if (s.values.isEmpty()) {
        return Vital(
            label = "Steps",
            icon = DashboardIcons.Flame,
            value = "—",
            unit = null,
            delta = null,
            sparkline = List(9) { SPARK_FLAT },
        )
    }
    return Vital(
        label = "Steps",
        icon = DashboardIcons.Flame,
        value = "%,d".format(s.values.last().roundToInt()),
        unit = null,
        delta = delta(sevenDayDelta(s), lowerBetter = false) { "%,d".format(it.roundToInt()) },
        sparkline = metricSparkline(s.values),
    )
}

/**
 * Convenience: the four live metric vitals, in dashboard tile order
 * (Resting HR, HRV, Sleep, Steps). Used by both the phone and foldable grids.
 */
fun metricVitals(points: List<DailyMetricPoint>): List<Vital> = listOf(
    restingHrVital(points),
    hrvVital(points),
    sleepVital(points),
    stepsVital(points),
)
