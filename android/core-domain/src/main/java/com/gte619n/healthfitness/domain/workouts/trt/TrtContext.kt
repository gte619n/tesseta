package com.gte619n.healthfitness.domain.workouts.trt

import java.time.LocalDate

// Grounded TRT decision-support context surfaced in the workout-program designer
// chat (IMPL-AND-18 / ADR-0015). Mirrors the LOCKED backend
// `GET api/me/workout-programs/chat/trt-context` shape: monitoring-panel markers
// vs. range with trend + status, plus any hard danger flags.

/** Direction of a marker across the two most-recent reports. */
enum class TrtTrend { RISING, FALLING, STABLE, UNKNOWN }

/** Where a marker's latest value sits relative to its reference range. */
enum class TrtMarkerStatus { LOW, IN_RANGE, HIGH, WATCH, UNKNOWN }

/** Severity of a danger flag (S6e mandatory hard alerts). */
enum class DangerSeverity { WARNING, DANGER }

data class TrtMarker(
    val name: String,
    val label: String,
    val value: Double?,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
    val sampleDate: LocalDate?,
    val trend: TrtTrend,
    val status: TrtMarkerStatus,
)

data class DangerFlag(
    val marker: String,
    val severity: DangerSeverity,
    val message: String,
)

data class TrtContext(
    val onTrt: Boolean,
    val markers: List<TrtMarker> = emptyList(),
    val dangerFlags: List<DangerFlag> = emptyList(),
) {
    /** Show the TRT panel only when on TRT or there is at least one marker. */
    val shouldShow: Boolean
        get() = onTrt || markers.isNotEmpty()
}
